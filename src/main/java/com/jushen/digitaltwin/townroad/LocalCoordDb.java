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
     *
     * @param province 省名
     * @param city     市名
     * @param district 区名
     * @param name     地名
     * @param coords   [lng, lat]
     */
    public synchronized void put(String province, String city, String district, String name, double[] coords) {
        if (province == null || city == null || district == null || name == null) return;
        if (coords == null || coords.length < 2) return;

        String full = province + city + district + name;
        fullPathIndex.put(full, coords);
        if (name.equals(district)) {
            fullPathIndex.put(province + city + district, coords);
        }
        fullPathIndex.putIfAbsent(name, coords);

        // 写回文件
        String relativeKey = province + "/" + city + "/" + district + ".json";
        Map<String, double[]> data = fileData.computeIfAbsent(relativeKey, k -> new LinkedHashMap<>());
        data.put(name, coords);

        File file = fileMap.get(relativeKey);
        if (file != null) {
            try {
                // 先写临时文件再原子替换
                Path tempPath = Paths.get(file.getParent(), district + ".tmp.json");
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempPath.toFile(), data);
                Files.move(tempPath, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                log.info("[CoordDb] saved {} -> {} to {}", name, coords, relativeKey);
            } catch (IOException e) {
                log.warn("[CoordDb] failed to write {}: {}", relativeKey, e.getMessage());
            }
        } else {
            log.debug("[CoordDb] no writable file for {}, entry cached in memory only", relativeKey);
        }
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
