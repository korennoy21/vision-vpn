package dev.sever.vpn;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import okhttp3.*;
import org.json.JSONObject;

/** Best-effort VISION Control telemetry. A telemetry failure must never tear down the VPN. */
final class TelemetryReporter {
    private TelemetryReporter() {}

    static void send(Profile profile, NetworkTracker network, Predicate<java.net.Socket> protect, String state) {
        if (profile == null || profile.subscriptionUrl == null || network == null) return;
        OkHttpClient client = null;
        try {
            URI subscription = new URI(profile.subscriptionUrl);
            URI endpoint = new URI(subscription.getScheme(), null, subscription.getHost(), subscription.getPort(),
                "/api/client-telemetry", null, null);
            Dns dns = hostname -> Arrays.asList(network.resolve(hostname));
            client = new OkHttpClient.Builder()
                .dns(dns)
                .socketFactory(new WebTransport.ProtectedSockets(protect, network))
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(7, TimeUnit.SECONDS).build();

            JSONObject json = new JSONObject();
            json.put("platform", "android");
            json.put("engine", ProtocolDetector.displayName(VisionState.protocol));
            json.put("transport", ProtocolDetector.VISION.equals(VisionState.protocol) ? "wss" : VisionState.protocol);
            json.put("state", state == null ? (VisionState.running ? "connected" : VisionState.status) : state);
            json.put("endpoint", VisionState.endpoint == null ? "" : VisionState.endpoint);
            json.put("network", VisionState.network == null ? "unknown" : VisionState.network);
            json.put("rx", Math.max(0, VisionState.downloaded.get()));
            json.put("tx", Math.max(0, VisionState.uploaded.get()));
            if (VisionState.lastRttMs >= 0) json.put("rtt_ms", VisionState.lastRttMs); else json.put("rtt_ms", JSONObject.NULL);
            json.put("reconnects", Math.max(0, VisionState.reconnects));
            json.put("app_version", BuildConfig.VERSION_NAME);

            RequestBody body = RequestBody.create(json.toString(), MediaType.get("application/json; charset=utf-8"));
            Request request = new Request.Builder().url(endpoint.toString()).post(body)
                .header("Authorization", Credentials.basic(profile.user, profile.token, StandardCharsets.UTF_8)).build();
            try (Response response = client.newCall(request).execute()) {
                // 2xx is sufficient. Do not log status or credentials from a managed profile.
            }
        } catch (Exception ignored) {
            // Telemetry is deliberately non-fatal.
        } finally {
            if (client != null) {
                client.dispatcher().cancelAll();
                client.connectionPool().evictAll();
                client.dispatcher().executorService().shutdown();
            }
        }
    }
}
