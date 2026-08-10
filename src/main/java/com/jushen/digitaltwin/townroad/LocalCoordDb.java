package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本地坐标数据库：按 省/市/区.json 分层存储，格式 {"地名": [lng, lat]}。
 * 启动时扫描 classpath:coord-db/ 下所有 JSON，构建内存索引。
 * 支持运行时新增条目并写回文件（仅 dev 模式或文件系统可写时）。
 */
@Component
public class LocalCoordDb {

    private static final Logger log = LoggerFactory.getLogger(LocalCoordDb.class);

    /**
     * 从文件路径中提取层级：山东省/滨州市/无棣县.json → [山东省, 滨州市, 无棣县]
     */
    private static final Pattern PATH_PATTERN = Pattern.compile(
            "coord-db[/\\\\](.+?)[/\\\\](.+?)[/\\\\](.+?)\\.json"
    );

    private final ObjectMapper objectMapper;
    private final ResourcePatternResolver resourceResolver;

    /**
     * 全路径 → [lng, lat]，如 "山东省滨州市无棣县东风港站" → [117.916, 37.748]
     */
    private final Map<String, double[]> fullPathIndex = new ConcurrentHashMap<>();

    /**
     * 文件名 → 源文件 File 映射，用于写回。
     * key: "山东省/滨州市/无棣县.json"
     */
    private final Map<String, File> fileMap = new ConcurrentHashMap<>();

    /**
     * 文件 → 已加载的原始数据 Map，用于合并写入。
     * key: "山东省/滨州市/无棣县.json", value: {地名: [lng, lat]}
     */
    private final Map<String, Map<String, double[]>> fileData = new ConcurrentHashMap<>();

    public LocalCoordDb(ObjectMapper objectMapper, ResourcePatternResolver resourceResolver) {
        this.objectMapper = objectMapper;
        this.resourceResolver = resourceResolver;
    }

    @PostConstruct
    public void loadAll() {
        try {
            Resource[] resources = resourceResolver.getResources("classpath*:coord-db/**/*.json");
            log.info("[CoordDb] scanning {} json files under coord-db/", resources.length);

            int loaded = 0;
            for (Resource resource : resources) {
                try {
                    String path = resource.getURL().getPath();
                    Matcher m = PATH_PATTERN.matcher(path);
                    if (!m.find()) {
                        log.debug("[CoordDb] skip unrecognized path: {}", path);
                        continue;
                    }

                    String province = m.group(1);  // 山东省
                    String city = m.group(2);      // 滨州市
                    String districtFile = m.group(3); // 无棣县

                    Map<String, double[]> entries = objectMapper.readValue(
                            resource.getInputStream(),
                            new TypeReference<LinkedHashMap<String, double[]>>() {}
                    );

                    String relativeKey = province + "/" + city + "/" + districtFile + ".json";
                    fileData.put(relativeKey, new LinkedHashMap<>(entries));

                    // 记录文件引用用于写回
                    try {
                        File file = resource.getFile();
                        fileMap.put(relativeKey, file);
                    } catch (Exception fileEx) {
                        // JAR 内的资源没有 File 引用，写回时会降级
                        log.debug("[CoordDb] no file handle for {}: {}", relativeKey, fileEx.getMessage());
                    }

                    for (Map.Entry<String, double[]> entry : entries.entrySet()) {
                        String name = entry.getKey();
                        double[] coords = entry.getValue();
                        if (coords == null || coords.length < 2) continue;

                        // 构建多级索引
                        // 1. 省+市+区+名
                        String full = province + city + districtFile + name;
                        fullPathIndex.put(full, coords);
                        // 2. 省+市+区（如果 key 就是区名本身如 "无棣县"）
                        if (name.equals(districtFile)) {
                            fullPathIndex.put(province + city + districtFile, coords);
                        }
                        // 3. 名本身（兜底）
                        fullPathIndex.putIfAbsent(name, coords);
                    }

                    loaded += entries.size();
                } catch (Exception e) {
                    log.warn("[CoordDb] failed to load {}: {}", resource.getFilename(), e.getMessage());
                }
            }

            log.info("[CoordDb] loaded {} entries from {} files", loaded, resources.length);
        } catch (IOException e) {
            log.error("[CoordDb] failed to scan coord-db/: {}", e.getMessage());
        }
    }

    /**
     * 按省/市/区/名查找坐标。
     *
     * @param province 省名（如"山东省"）
     * @param city     市名（如"滨州市"）
     * @param district 区名（如"无棣县"）
     * @param name     具体地名（如"东风港站"）
     * @return [lng, lat] 或 null
     */
    public double[] resolve(String province, String city, String district, String name) {
        // 1. 完整路径：省+市+区+名
        if (province != null && city != null && district != null && name != null) {
            double[] coords = fullPathIndex.get(province + city + district + name);
            if (coords != null) return coords;
        }

        // 2. 省+市+区
        if (province != null && city != null && district != null) {
            double[] coords = fullPathIndex.get(province + city + district);
            if (coords != null) return coords;
        }

        // 3. 直接用名称查
        if (name != null) {
            double[] coords = fullPathIndex.get(name);
            if (coords != null) return coords;
        }

        // 4. 省+市
        if (province != null && city != null) {
            double[] coords = fullPathIndex.get(province + city);
            if (coords != null) return coords;
        }

        return null;
    }

    /**
     * 查询任意完整 key（兼容旧的 nameToCoords 接口）。
     */
    public double[] get(String key) {
        return fullPathIndex.get(key);
    }

    /**
     * 新增一条坐标记录并写回对应的 JSON 文件。
     * 如果省/市/区对应的目录或文件不存在，会自动创建。
     *
     * @param province 省名（可 null）
     * @param city     市名（可 null）
     * @param district 区名（可 null）
     * @param name     地名
     * @param coords   [lng, lat]
     */
    public synchronized void put(String province, String city, String district, String name, double[] coords) {
        if (name == null || name.isBlank()) return;
        if (coords == null || coords.length < 2) return;

        // 兜底：province/city/district 为 null 时用占位符
        String effProvince = province != null && !province.isBlank() ? province : "_unknown";
        String effCity = city != null && !city.isBlank() ? city : "_unknown";
        String effDistrict = district != null && !district.isBlank() ? district : "_unknown";

        // 1. 更新内存索引
        String full = effProvince + effCity + effDistrict + name;
        fullPathIndex.put(full, coords);
        if (name.equals(effDistrict)) {
            fullPathIndex.put(effProvince + effCity + effDistrict, coords);
        }
        fullPathIndex.putIfAbsent(name, coords);

        // 2. 写回文件
        String relativeKey = effProvince + "/" + effCity + "/" + effDistrict + ".json";
        Map<String, double[]> data = fileData.computeIfAbsent(relativeKey, k -> new LinkedHashMap<>());
        data.put(name, coords);

        File file = fileMap.get(relativeKey);
        if (file == null) {
            file = findOrCreateCoordFile(effProvince, effCity, effDistrict);
            if (file != null) {
                fileMap.put(relativeKey, file);
            }
        }

        if (file != null) {
            // 重定向 target/classes/ → src/main/resources/（防止 mvn clean 丢失）
            file = redirectToSource(file);
            try {
                Path tempPath = Paths.get(file.getParent(), effDistrict + ".tmp.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), data);
                Files.move(tempPath, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.info("[CoordDb] saved {} -> {} to {}", name, coords, relativeKey);
            } catch (IOException e) {
                log.warn("[CoordDb] failed to write {}: {}", relativeKey, e.getMessage());
            }
        } else {
            log.warn("[CoordDb] cannot persist {}, entry cached in memory only: {}/{}/{}/{}.json",
                    name, effProvince, effCity, effDistrict);
        }
    }

    /**
     * 尝试从已知的 coord-db 基础路径推导新文件的写入路径，必要时创建目录。
     */
    private File findOrCreateCoordFile(String province, String city, String district) {
        // 从已有的 fileMap 中找一个文件，推导出基础路径
        File baseDir = resolveBaseDir();
        if (baseDir == null) return null;

        File provinceDir = new File(baseDir, province);
        File cityDir = new File(provinceDir, city);
        File jsonFile = new File(cityDir, district + ".json");

        try {
            if (!cityDir.exists()) {
                Files.createDirectories(cityDir.toPath());
                log.info("[CoordDb] created directory: {}", cityDir);
            }
            if (!jsonFile.exists()) {
                Files.createFile(jsonFile.toPath());
                // 写入空 JSON 初始内容
                Files.writeString(jsonFile.toPath(), "{}\n", StandardCharsets.UTF_8);
                log.info("[CoordDb] created file: {}", jsonFile);
            }
            return jsonFile;
        } catch (IOException e) {
            log.warn("[CoordDb] failed to create file {}: {}", jsonFile, e.getMessage());
            return null;
        }
    }

    /**
     * 从已有的 fileMap 或 classpath 推导出 coord-db 的文件系统基础路径。
     * 优先使用 src/main/resources/coord-db/（源码目录），
     * 避免写到 target/classes/ 下被 mvn clean 清除。
     */
    private File resolveBaseDir() {
        // 1. 优先：src/main/resources/coord-db（开发环境源码目录）
        File srcDir = new File("src/main/resources/coord-db");
        if (srcDir.exists() && srcDir.isDirectory()) return srcDir;

        // 2. 从 fileMap 推导（可能是 target/classes/...）
        for (File file : fileMap.values()) {
            File parent = file.getParentFile();
            if (parent != null) {
                File grandParent = parent.getParentFile();
                if (grandParent != null) {
                    File base = grandParent.getParentFile();
                    if (base != null && base.exists()) {
                        // 如果推导到 target/classes/，尝试换成源码目录
                        String basePath = base.getAbsolutePath().replace('\\', '/');
                        String srcPath = basePath.replace("target/classes/", "src/main/resources/");
                        if (!srcPath.equals(basePath)) {
                            File srcCandidate = new File(srcPath);
                            if (srcCandidate.exists()) return srcCandidate;
                        }
                        return base;
                    }
                }
            }
        }

        // 3. 回退：classpath
        try {
            Resource resource = resourceResolver.getResource("classpath:coord-db/");
            File file = resource.getFile();
            if (file.exists()) {
                String path = file.getAbsolutePath().replace('\\', '/');
                String srcPath = path.replace("target/classes/", "src/main/resources/");
                if (!srcPath.equals(path)) {
                    File srcCandidate = new File(srcPath);
                    if (srcCandidate.exists()) return srcCandidate;
                }
                return file;
            }
        } catch (Exception ignored) {}

        return null;
    }

    /** 如果文件在 target/classes/ 下，重定向到 src/main/resources/ */
    private File redirectToSource(File targetFile) {
        String path = targetFile.getAbsolutePath().replace('\\', '/');
        if (!path.contains("target/classes/")) return targetFile;
        String srcPath = path.replace("target/classes/", "src/main/resources/");
        File srcFile = new File(srcPath);
        // 确保源码目录存在
        if (!srcFile.getParentFile().exists()) {
            try {
                Files.createDirectories(srcFile.getParentFile().toPath());
            } catch (IOException e) {
                log.warn("[CoordDb] cannot create source dir: {}", srcFile.getParent());
                return targetFile; // 回退
            }
        }
        return srcFile;
    }

    public int size() {
        return fullPathIndex.size();
    }

    /**
     * 对外暴露内存索引（只读），用于兼容旧接口。
     */
    public Map<String, double[]> asFlatMap() {
        return Map.copyOf(fullPathIndex);
    }
}
