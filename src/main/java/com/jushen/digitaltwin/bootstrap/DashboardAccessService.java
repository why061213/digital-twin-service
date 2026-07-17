package com.jushen.digitaltwin.bootstrap;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DashboardAccessService {

    private static final Logger log = LoggerFactory.getLogger(DashboardAccessService.class);
    private static final Pattern MAC_PATTERN = Pattern.compile("(?i)(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}");
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(2);

    private final DashboardAccessProperties properties;

    public DashboardAccessService(DashboardAccessProperties properties) {
        this.properties = properties;
    }

    public Verification verify(HttpServletRequest request, String suppliedDeviceToken) {
        String remoteAddress = normalizeRemoteAddress(request.getRemoteAddr());
        if (!properties.isEnabled()) {
            return new Verification(true, "disabled", remoteAddress, null, "设备验证未启用");
        }

        if (tokenMatches(suppliedDeviceToken)) {
            return new Verification(true, "device-token", remoteAddress, null, "设备令牌验证通过");
        }

        boolean localHost = isLocalHost(remoteAddress);
        Set<String> allowedMacs = normalizedAllowedMacs();
        List<String> detectedMacs = localHost ? localMacAddresses() : resolveNeighborMacAddresses(remoteAddress);

        for (String mac : detectedMacs) {
            if (allowedMacs.contains(mac)) {
                return new Verification(true, "mac-whitelist", remoteAddress, maskMac(mac), "局域网设备验证通过");
            }
        }

        if (localHost && properties.isTrustLoopback() && allowedMacs.isEmpty()) {
            return new Verification(true, "loopback", remoteAddress, null, "后端本机验证通过");
        }

        String detectedMac = detectedMacs.isEmpty() ? null : detectedMacs.get(0);
        if (detectedMac != null) {
            log.info("Dashboard device rejected: remoteAddress={}, detectedMac={}", remoteAddress, detectedMac);
        }
        String message = allowedMacs.isEmpty()
                ? "尚未配置局域网 MAC 白名单"
                : detectedMac == null
                    ? "无法从邻居表识别设备，请确认处于同一局域网或配置设备令牌"
                    : "当前设备不在 MAC 白名单中";
        return new Verification(false, "denied", remoteAddress, maskMac(detectedMac), message);
    }

    private boolean tokenMatches(String suppliedDeviceToken) {
        String expected = properties.getDeviceToken().trim();
        return !expected.isEmpty() && expected.equals(suppliedDeviceToken == null ? "" : suppliedDeviceToken.trim());
    }

    private Set<String> normalizedAllowedMacs() {
        Set<String> result = new HashSet<>();
        for (String value : properties.getAllowedMacAddresses()) {
            String normalized = normalizeMac(value);
            if (normalized != null) result.add(normalized);
        }
        return result;
    }

    private List<String> localMacAddresses() {
        List<String> result = new ArrayList<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (!networkInterface.isUp() || networkInterface.isLoopback()) continue;
                String mac = formatMac(networkInterface.getHardwareAddress());
                if (mac != null && !result.contains(mac)) result.add(mac);
            }
        } catch (Exception e) {
            log.debug("Failed to inspect local network interfaces", e);
        }
        return result;
    }

    private List<String> resolveNeighborMacAddresses(String remoteAddress) {
        List<String> result = new ArrayList<>();
        if (remoteAddress == null || remoteAddress.isBlank()) return result;
        try {
            InetAddress.getByName(remoteAddress).isReachable(300);
            boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
            List<String> command = windows
                    ? List.of("arp", "-a", remoteAddress)
                    : List.of("ip", "neigh", "show", remoteAddress);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return result;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher matcher = MAC_PATTERN.matcher(line);
                    while (matcher.find()) {
                        String mac = normalizeMac(matcher.group());
                        if (mac != null && !result.contains(mac)) result.add(mac);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to resolve neighbor MAC for {}", remoteAddress, e);
        }
        return result;
    }

    private boolean isLocalHost(String address) {
        try {
            if (address == null) return false;
            InetAddress inetAddress = InetAddress.getByName(address);
            return inetAddress.isLoopbackAddress() || NetworkInterface.getByInetAddress(inetAddress) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String normalizeRemoteAddress(String address) {
        if (address != null && address.startsWith("::ffff:")) return address.substring(7);
        return address;
    }

    private String formatMac(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) {
            if (!value.isEmpty()) value.append(':');
            value.append(String.format("%02X", item & 0xff));
        }
        return value.toString();
    }

    private String normalizeMac(String value) {
        if (value == null) return null;
        String hex = value.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(Locale.ROOT);
        if (hex.length() != 12) return null;
        return String.join(":", hex.substring(0, 2), hex.substring(2, 4), hex.substring(4, 6),
                hex.substring(6, 8), hex.substring(8, 10), hex.substring(10, 12));
    }

    private String maskMac(String value) {
        String mac = normalizeMac(value);
        return mac == null ? null : "**:**:**:" + mac.substring(9);
    }

    public record Verification(
            boolean authorized,
            String method,
            String remoteAddress,
            String deviceIdentity,
            String message
    ) {
    }
}
