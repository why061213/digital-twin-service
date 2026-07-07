package com.jushen.digitaltwin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.atomic.AtomicBoolean;

@EnableScheduling
@SpringBootApplication
public class DigitalTwinBackendApplication {
    private static final AtomicBoolean RESTARTING = new AtomicBoolean(false);
    private static ConfigurableApplicationContext context;
    private static String[] args;

    public static void main(String[] args) {
        DigitalTwinBackendApplication.args = args;
        context = SpringApplication.run(DigitalTwinBackendApplication.class, args);
    }

    public static boolean restart(long delayMillis) {
        if (!RESTARTING.compareAndSet(false, true)) {
            return false;
        }

        Thread restartThread = new Thread(() -> {
            try {
                Thread.sleep(Math.max(0, delayMillis));
                ConfigurableApplicationContext previousContext = context;
                if (previousContext != null) {
                    previousContext.close();
                }
                context = SpringApplication.run(DigitalTwinBackendApplication.class, args);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                RESTARTING.set(false);
            }
        }, "digital-twin-backend-restart");
        restartThread.setDaemon(false);
        restartThread.start();
        return true;
    }
}
    
