package com.jushen.digitaltwin;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StartupLogger {
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        System.out.println("============================================");
        System.out.println(" 配置管理页面: http://localhost:8080/ConfigControl.html");
        System.out.println("============================================");
    }
}