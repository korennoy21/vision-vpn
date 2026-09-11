package dev.sever.vpn;

import org.json.JSONObject;
import java.util.Locale;
import java.util.regex.Pattern;

/** Deterministic profile type detector. It never guesses an unsupported config as VISION. */
public final class ProtocolDetector {
    public static final String VISION = "sever1";
    public static final String WIREGUARD = "wireguard";
    public static final String AMNEZIAWG = "amneziawg";
    public static final String OPENVPN = "openvpn";
    public static final String VLESS = "vless";
    public static final String VMESS = "vmess";
    public static final String TROJAN = "trojan";
    public static final String SHADOWSOCKS = "shadowsocks";

    private static final Pattern AWG = Pattern.compile("(?im)^\\s*(jc|jmin|jmax|s1|s2|h1|h2|h3|h4)\\s*=");
    private ProtocolDetector() {}

    public static String detect(String raw) throws Exception {
        if (raw == null) throw new Exception("Пустая конфигурация");
        String v = raw.trim();
        if (v.isEmpty()) throw new Exception("Пустая конфигурация");
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("vless://")) return VLESS;
        if (lower.startsWith("vmess://")) return VMESS;
        if (lower.startsWith("trojan://")) return TROJAN;
        if (lower.startsWith("ss://")) return SHADOWSOCKS;
        if (lower.startsWith("{") && lower.endsWith("}")) {
            JSONObject object = new JSONObject(v);
            if ("sever1".equals(object.optString("protocol")) && "wss".equals(object.optString("transport"))) {
                new Profile(v); // full validation
                return VISION;
            }
        }
        if (lower.contains("[interface]") && lower.contains("[peer]")) {
            return AWG.matcher(v).find() ? AMNEZIAWG : WIREGUARD;
        }
        if (looksLikeOpenVpn(lower)) return OPENVPN;
        throw new Exception("Не удалось определить формат конфигурации");
    }

    private static boolean looksLikeOpenVpn(String lower) {
        boolean mode = lower.matches("(?s).*(^|\\n)\\s*(client|dev\\s+tun|dev\\s+tap)(\\s|$).*");
        boolean remote = lower.matches("(?s).*(^|\\n)\\s*remote\\s+\\S+.*");
        return mode && remote;
    }

    public static String displayName(String protocol) {
        return switch (protocol) {
            case VISION -> "VISION Secure";
            case WIREGUARD -> "WireGuard";
            case AMNEZIAWG -> "AmneziaWG";
            case OPENVPN -> "OpenVPN";
            case VLESS -> "VLESS / Xray";
            case VMESS -> "VMess / Xray";
            case TROJAN -> "Trojan / Xray";
            case SHADOWSOCKS -> "Shadowsocks / Xray";
            default -> protocol;
        };
    }

    public static String defaultName(String protocol, String raw) {
        try {
            if (VISION.equals(protocol)) return new JSONObject(raw).optString("name", "VISION");
        } catch (Exception ignored) {}
        return displayName(protocol);
    }
}
