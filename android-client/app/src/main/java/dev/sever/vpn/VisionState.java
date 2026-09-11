package dev.sever.vpn;

import java.util.concurrent.atomic.AtomicLong;

/** Process-local live connection state shared by UI and engine adapters. */
public final class VisionState {
    public static volatile boolean running = false;
    public static volatile boolean connecting = false;
    public static volatile String status = "Отключено";
    public static volatile String protocol = "";
    public static volatile String endpoint = "";
    public static volatile String network = "unknown";
    public static volatile long connectedAt = 0;
    public static volatile int reconnects = 0;
    public static volatile long lastRttMs = -1;
    public static final AtomicLong uploaded = new AtomicLong();
    public static final AtomicLong downloaded = new AtomicLong();

    private VisionState() {}

    public static synchronized void starting(String p) {
        connecting = true; running = false; protocol = p; endpoint = "";
        status = "Подключение • " + ProtocolDetector.displayName(p);
        uploaded.set(0); downloaded.set(0); lastRttMs = -1;
    }
    public static synchronized void connected(String p, String ep) {
        protocol = p; endpoint = ep == null ? "" : ep; connecting = false; running = true;
        connectedAt = System.currentTimeMillis(); status = "Подключено • " + ProtocolDetector.displayName(p);
    }
    public static synchronized void stopped() {
        connecting = false; running = false; status = "Отключено"; endpoint = ""; connectedAt = 0; lastRttMs = -1;
    }
    public static synchronized void failed(String text) {
        connecting = false; running = false; status = text == null || text.isBlank() ? "Ошибка подключения" : text;
    }
}
