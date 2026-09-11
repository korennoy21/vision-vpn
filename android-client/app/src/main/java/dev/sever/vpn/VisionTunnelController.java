package dev.sever.vpn;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Single connect/disconnect entry point used by the one-button VISION UI. */
public final class VisionTunnelController {
    public interface Callback { void done(Exception error); }
    private VisionTunnelController() {}

    public static void connect(Context context, Callback callback) {
        new Thread(() -> {
            Exception error = null;
            try {
                UnifiedProfile p = ProfileStore.active(context);
                disconnectEnginesExcept(context, p.protocol);
                VisionState.starting(p.protocol);
                switch (p.protocol) {
                    case ProtocolDetector.VISION -> {
                        Intent i = new Intent(context, SeverVpnService.class).setAction("CONNECT");
                        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i); else context.startService(i);
                    }
                    case ProtocolDetector.WIREGUARD -> WireGuardEngine.connect(context, p);
                    case ProtocolDetector.AMNEZIAWG -> AmneziaWgEngine.connect(context, p);
                    case ProtocolDetector.OPENVPN -> throw new Exception("OpenVPN core ещё не включён в эту сборку");
                    case ProtocolDetector.VLESS, ProtocolDetector.VMESS, ProtocolDetector.TROJAN, ProtocolDetector.SHADOWSOCKS ->
                        throw new Exception("Xray core ещё не включён в эту сборку");
                    default -> throw new Exception("Неизвестный VPN-движок");
                }
            } catch (Exception e) { error = e; VisionState.failed(e.getMessage()); }
            if (callback != null) callback.done(error);
        }, "vision-connect").start();
    }

    public static void disconnect(Context context) {
        VisionState.status = "Отключение…";
        Intent stop = new Intent(context, SeverVpnService.class).setAction("STOP");
        try { context.startService(stop); } catch (Exception ignored) {}
        WireGuardEngine.disconnect();
        AmneziaWgEngine.disconnect();
        VisionState.stopped();
    }

    private static void disconnectEnginesExcept(Context context, String protocol) {
        if (!ProtocolDetector.WIREGUARD.equals(protocol)) WireGuardEngine.disconnect();
        if (!ProtocolDetector.AMNEZIAWG.equals(protocol)) AmneziaWgEngine.disconnect();
        if (!ProtocolDetector.VISION.equals(protocol)) {
            try { context.startService(new Intent(context, SeverVpnService.class).setAction("STOP")); } catch (Exception ignored) {}
        }
    }
}
