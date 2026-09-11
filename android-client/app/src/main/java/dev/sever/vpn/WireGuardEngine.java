package dev.sever.vpn;

import android.content.Context;
import com.wireguard.android.backend.GoBackend;
import com.wireguard.android.backend.Tunnel;
import com.wireguard.config.Config;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Real WireGuard userspace backend powered by WireGuard's embeddable Android tunnel library. */
public final class WireGuardEngine {
    private static final Object LOCK = new Object();
    private static GoBackend backend;
    private static Tunnel tunnel;

    private WireGuardEngine() {}

    public static void connect(Context context, UnifiedProfile profile) throws Exception {
        String raw = RoutingConfigInjector.apply(profile.raw, context);
        Config config = Config.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        synchronized (LOCK) {
            if (backend == null) backend = new GoBackend(context.getApplicationContext());
            if (tunnel == null) tunnel = new Tunnel() {
                @Override public String getName() { return "visionwg"; }
                @Override public void onStateChange(State newState) {
                    if (newState == State.UP) VisionState.connected(ProtocolDetector.WIREGUARD, "WireGuard");
                    else if (VisionState.protocol.equals(ProtocolDetector.WIREGUARD)) VisionState.stopped();
                }
            };
            backend.setState(tunnel, Tunnel.State.UP, config);
            VisionState.connected(ProtocolDetector.WIREGUARD, "WireGuard");
        }
    }

    public static void disconnect() {
        synchronized (LOCK) {
            try { if (backend != null && tunnel != null) backend.setState(tunnel, Tunnel.State.DOWN, null); }
            catch (Exception ignored) {}
            if (VisionState.protocol.equals(ProtocolDetector.WIREGUARD)) VisionState.stopped();
        }
    }
}
