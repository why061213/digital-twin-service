package com.jushen.digitaltwin.townroad;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class TownRoadExternalOrderClient {

    private final ObjectMapper objectMapper;
    private final TownRoadExternalOrderProperties properties;
    private final HttpClient httpClient;

    public TownRoadExternalOrderClient(
            ObjectMapper objectMapper,
            TownRoadExternalOrderProperties properties
    ) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
    }

    public List<ExternalOrderRecord> postOrders(Map<String, Object> payload) {
        if (properties.getPostUrl() == null || properties.getPostUrl().isBlank()) {
            return List.of();
        }

        try {
            String requestBody = objectMapper.writeValueAsString(payload == null ? Map.of() : payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.getPostUrl()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("External order API status = " + response.statusCode());
            }

            return parseRecords(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to fetch external orders", e);
        }
    }

    private List<ExternalOrderRecord> parseRecords(String responseBody) throws Exception {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }

        String trimmed = responseBody.trim();

        if (trimmed.startsWith("[")) {
            return objectMapper.readValue(
                    trimmed,
                    new TypeReference<List<ExternalOrderRecord>>() {
                    }
            );
        }

        Map<String, Object> root = objectMapper.readValue(
                trimmed,
                new TypeReference<Map<String, Object>>() {
                }
        );

        // 判断外部 API 是否返回成功
        Object success = root.get("success");
        Object code = root.get("code");
        boolean ok = Boolean.TRUE.equals(success)
                || "200".equals(String.valueOf(code))
                || "0".equals(String.valueOf(code));
        if (!ok) {
            Object message = root.get("message");
            System.err.println("[TownRoadExtClient] external API returned failure: success="
                    + success + ", code=" + code + ", message=" + message);
            return List.of();
        }

        Object records = root.get("records");
        if (records == null) records = root.get("data");
        if (records == null) records = root.get("list");

        if (records == null) {
            return List.of();
        }

        return objectMapper.convertValue(
                records,
                new TypeReference<List<ExternalOrderRecord>>() {
                }
        );
    }
}