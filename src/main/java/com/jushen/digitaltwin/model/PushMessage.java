package com.jushen.digitaltwin.model;

import java.time.Instant;

public record PushMessage(String type, Instant timestamp, Object data) {

    public static PushMessage of(String type, Object data) {
        return new PushMessage(type, Instant.now(), data);
    }
}
