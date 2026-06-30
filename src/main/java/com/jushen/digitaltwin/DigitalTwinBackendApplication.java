package com.jushen.digitaltwin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class DigitalTwinBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(DigitalTwinBackendApplication.class, args);
    }
}
