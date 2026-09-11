package dev.sever.vpn;

import android.content.Context;
import org.amnezia.awg.backend.GoBackend;
import org.amnezia.awg.backend.Tunnel;
import org.amnezia.awg.config.Config;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** AmneziaWG userspace backend. */
public final class AmneziaWgEngine {
    private static final Object LOCK = new Object();
    private static GoBackend backend;
    private static Tunnel tunnel;

    private AmneziaWgEngine() {}

    public static void connect(Context context, UnifiedProfile profile) throws Exception {
        String raw = RoutingConfigInjector.apply(profile.raw, context);
        Config config = Config.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        synchronized (LOCK) {
            if (backend == null) backend = new GoBackend(context.getApplicationContext());
            if (tunnel == null) tunnel = new Tunnel() {
                @Override public String getName() { return "visionawg"; }
                @Override public void onStateChange(State newState) {
                    if (newState == State.UP) VisionState.connected(ProtocolDetector.AMNEZIAWG, "AmneziaWG");
                    else if (VisionState.protocol.equals(ProtocolDetector.AMNEZIAWG)) VisionState.stopped();
                }
            };
            backend.setState(tunnel, Tunnel.State.UP, config);
            VisionState.connected(ProtocolDetector.AMNEZIAWG, "AmneziaWG");
        }
    }

    public static void disconnect() {
        synchronized (LOCK) {
            try { if (backend != null && tunnel != null) backend.setState(tunnel, Tunnel.State.DOWN, null); }
            catch (Exception ignored) {}
            if (VisionState.protocol.equals(ProtocolDetector.AMNEZIAWG)) VisionState.stopped();
        }
    }
}
