// ConfigService.java
package com.jushen.digitaltwin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jushen.digitaltwin.config.ConfigData;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class ConfigService {
    private final ObjectMapper objectMapper;
    private final Path configFilePath;
    private volatile ConfigData configData;

    public ConfigService(ObjectMapper objectMapper,
                         @Value("${app.config.file:config.json}") String configFile) {
        this.objectMapper = objectMapper;
        this.configFilePath = Paths.get(configFile);
    }

    @PostConstruct
    public void loadConfig() {
        if (Files.exists(configFilePath)) {
            try {
                configData = objectMapper.readValue(configFilePath.toFile(), ConfigData.class);
                // 如果 JSON 文件缺少字段，会使用 ConfigData 的默认值（Jackson 默认行为）
            } catch (IOException e) {
                // 文件损坏时保留旧文件并打印错误，防止覆盖
                System.err.println("配置文件损坏，使用默认配置，请检查: " + configFilePath);
                configData = new ConfigData();
            }
        } else {
            configData = new ConfigData();
            saveConfig(); // 创建带默认值的文件
        }
    }

    public ConfigData getConfig() {
        ConfigData current = configData;
        return current == null ? new ConfigData() : current;
    }

    public synchronized void updateConfig(ConfigData newConfig) throws IOException {
        // 可在此处做校验
        this.configData = newConfig;
        saveConfig();
    }

    private void saveConfig() {
        try {
            Path parent = configFilePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(configFilePath.toFile(), configData);
        } catch (IOException e) {
            throw new RuntimeException("无法保存配置文件: " + configFilePath, e);
        }
    }
}
