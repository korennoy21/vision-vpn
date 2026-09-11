package dev.sever.vpn;

import java.util.Set;

/** Compile-time capability registry. Downloaded executable code is never loaded. */
public final class EngineRegistry {
    private static final Set<String> INSTALLED = Set.of(
        ProtocolDetector.VISION,
        ProtocolDetector.WIREGUARD,
        ProtocolDetector.AMNEZIAWG
    );

    private EngineRegistry() {}

    public static boolean installed(String protocol) { return INSTALLED.contains(protocol); }

    public static void requireInstalled(String protocol) throws Exception {
        if (!installed(protocol)) throw new Exception("Движок «" + protocol + "» не включён в эту сборку");
    }

    public static String capabilities() {
        return "VISION Secure (WSS), WireGuard и AmneziaWG — встроены.\n\n" +
            "OpenVPN / VLESS / VMess / Trojan / Shadowsocks — формат профиля распознаётся, " +
            "но native core ещё не включён в эту сборку.";
    }
}
