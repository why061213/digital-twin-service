package com.jushen.digitaltwin.bootstrap;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DashboardAccessTokenService {

    private static final String TOKEN_PREFIX = "jdt_";
    private static final Duration MIN_TTL = Duration.ofMinutes(5);
    private static final Duration MIN_REFRESH_AFTER = Duration.ofMinutes(1);

    private final DashboardAccessProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentHashMap<String, SessionRecord> sessions = new ConcurrentHashMap<>();

    public DashboardAccessTokenService(DashboardAccessProperties properties) {
        this.properties = properties;
    }

    public IssuedSession issue(DashboardAccessService.Verification verification) {
        if (verification == null || !verification.authorized()) return null;
        return issue(verification.method(), verification.deviceIdentity());
    }

    public IssuedSession refresh(String rawToken) {
        Validation validation = validate(rawToken);
        if (!validation.valid()) return null;

        Instant now = Instant.now();
        String digest = digest(rawToken);
        SessionRecord existing = sessions.get(digest);
        if (existing == null) return null;
        Duration grace = positive(properties.getSessionRefreshGrace(), Duration.ofMinutes(2));
        Instant graceExpiry = now.plus(grace);
        sessions.computeIfPresent(digest, (key, value) -> value.withExpiresAt(
                value.expiresAt().isBefore(graceExpiry) ? value.expiresAt() : graceExpiry
        ));
        return issue(existing.verificationMethod(), existing.deviceIdentity());
    }

    public Validation validate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Validation.invalid();
        Instant now = Instant.now();
        SessionRecord record = sessions.get(digest(rawToken));
        if (record == null) return Validation.invalid();
        if (!record.expiresAt().isAfter(now)) {
            sessions.remove(digest(rawToken), record);
            return Validation.invalid();
        }
        return new Validation(
                true,
                record.issuedAt(),
                record.refreshAfter(),
                record.expiresAt(),
                record.verificationMethod(),
                record.deviceIdentity()
        );
    }

    private IssuedSession issue(String verificationMethod, String deviceIdentity) {
        Instant now = Instant.now();
        cleanup(now);
        evictOldestIfNecessary();

        Duration ttl = positive(properties.getSessionTtl(), Duration.ofHours(8));
        if (ttl.compareTo(MIN_TTL) < 0) ttl = MIN_TTL;
        Duration refreshDelay = positive(properties.getSessionRefreshAfter(), ttl.dividedBy(2));
        if (refreshDelay.compareTo(MIN_REFRESH_AFTER) < 0) refreshDelay = MIN_REFRESH_AFTER;
        if (refreshDelay.compareTo(ttl) >= 0) refreshDelay = ttl.dividedBy(2);

        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String rawToken = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SessionRecord record = new SessionRecord(
                now,
                now.plus(refreshDelay),
                now.plus(ttl),
                verificationMethod == null ? "unknown" : verificationMethod,
                deviceIdentity
        );
        sessions.put(digest(rawToken), record);
        return new IssuedSession(
                rawToken,
                "Bearer",
                record.issuedAt(),
                record.refreshAfter(),
                record.expiresAt(),
                record.verificationMethod(),
                record.deviceIdentity()
        );
    }

    private void cleanup(Instant now) {
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void evictOldestIfNecessary() {
        int maxSessions = Math.max(8, properties.getMaxActiveSessions());
        while (sessions.size() >= maxSessions) {
            sessions.entrySet().stream()
                    .min(Comparator.comparing(entry -> entry.getValue().issuedAt()))
                    .ifPresentOrElse(entry -> sessions.remove(entry.getKey(), entry.getValue()), sessions::clear);
        }
    }

    private Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private String digest(String rawToken) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private record SessionRecord(
            Instant issuedAt,
            Instant refreshAfter,
            Instant expiresAt,
            String verificationMethod,
            String deviceIdentity
    ) {
        SessionRecord withExpiresAt(Instant nextExpiresAt) {
            return new SessionRecord(issuedAt, refreshAfter, nextExpiresAt, verificationMethod, deviceIdentity);
        }
    }

    public record IssuedSession(
            String accessToken,
            String tokenType,
            Instant issuedAt,
            Instant refreshAfter,
            Instant expiresAt,
            String verificationMethod,
            String deviceIdentity
    ) {
    }

    public record Validation(
            boolean valid,
            Instant issuedAt,
            Instant refreshAfter,
            Instant expiresAt,
            String verificationMethod,
            String deviceIdentity
    ) {
        static Validation invalid() {
            return new Validation(false, null, null, null, null, null);
        }
    }
}
