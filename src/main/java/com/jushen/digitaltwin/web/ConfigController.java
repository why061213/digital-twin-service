// ConfigController.java
package com.jushen.digitaltwin.web;

import com.jushen.digitaltwin.DigitalTwinBackendApplication;
import com.jushen.digitaltwin.config.ConfigData;
import com.jushen.digitaltwin.service.ConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @GetMapping("/config")
    public ResponseEntity<ConfigData> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PostMapping("/config")
    public ResponseEntity<?> updateConfig(@RequestBody ConfigData newConfig) {
        try {
            configService.updateConfig(newConfig);
            return ResponseEntity.ok(Map.of("message", "配置已更新，将在重启后生效"));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "保存配置失败"));
        }
    }

    @PostMapping("/restart")
    public ResponseEntity<?> restart() {
        boolean accepted = DigitalTwinBackendApplication.restart(800);
        if (!accepted) {
            return ResponseEntity.status(409).body(Map.of("message", "服务正在重启，请稍后再试"));
        }
        return ResponseEntity.accepted().body(Map.of("message", "服务正在重启..."));
    }
}
