package com.jushen.digitaltwin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "camera.sequence")
@Data
public class CameraProperties {
    /** 聚焦单个城市的停留时间（毫秒） */
    private long focusDuration = 20000;
    /** 俯瞰所有仓库的停留时间（毫秒） */
    private long overviewDuration = 20000;
    /** 初始聚焦总部（佛山）的停留时间（毫秒） */
    private long headquartersDuration = 20000;
}