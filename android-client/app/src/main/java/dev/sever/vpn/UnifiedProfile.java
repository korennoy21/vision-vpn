package dev.sever.vpn;

import org.json.JSONObject;
import java.util.UUID;

/** Encrypted-at-rest user profile used by every VPN engine. */
public final class UnifiedProfile {
    public final String id;
    public final String name;
    public final String protocol;
    public final String raw;
    public final long createdAt;

    public UnifiedProfile(String id, String name, String protocol, String raw, long createdAt) {
        this.id = id; this.name = name; this.protocol = protocol; this.raw = raw; this.createdAt = createdAt;
    }

    public static UnifiedProfile parse(String raw) throws Exception {
        if (raw == null || raw.length() > 1024 * 1024) throw new Exception("Конфигурация слишком большая");
        String protocol = ProtocolDetector.detect(raw);
        return new UnifiedProfile(UUID.randomUUID().toString(), ProtocolDetector.defaultName(protocol, raw), protocol, raw.trim(), System.currentTimeMillis());
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("protocol", protocol); o.put("raw", raw); o.put("created_at", createdAt);
        return o;
    }

    static UnifiedProfile fromJson(JSONObject o) throws Exception {
        String raw = o.getString("raw");
        String detected = ProtocolDetector.detect(raw);
        String protocol = o.optString("protocol", detected);
        if (!detected.equals(protocol)) throw new Exception("Тип профиля не совпадает с содержимым");
        return new UnifiedProfile(o.getString("id"), o.optString("name", ProtocolDetector.displayName(protocol)), protocol, raw, o.optLong("created_at", 0));
    }
}
