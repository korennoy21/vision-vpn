package dev.sever.vpn;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM profile vault. Migrates the single 0.1.0 VISION profile on first use. */
public final class ProfileStore {
    private static final String ALIAS = "vision-profile-vault-v2";
    private static final String PREFS = "vision_profiles_v2";
    private static final String IV = "iv", DATA = "data", ACTIVE = "active";
    private ProfileStore() {}

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
            gen.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }

    public static synchronized List<UnifiedProfile> all(Context context) throws Exception {
        migrate(context);
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.contains(DATA)) return new ArrayList<>();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(prefs.getString(IV, ""), Base64.NO_WRAP)));
        String json = new String(cipher.doFinal(Base64.decode(prefs.getString(DATA, ""), Base64.NO_WRAP)), StandardCharsets.UTF_8);
        JSONArray a = new JSONArray(json);
        ArrayList<UnifiedProfile> result = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) result.add(UnifiedProfile.fromJson(a.getJSONObject(i)));
        return result;
    }

    public static synchronized UnifiedProfile active(Context context) throws Exception {
        List<UnifiedProfile> values = all(context);
        if (values.isEmpty()) throw new Exception("Импортируйте профиль VPN");
        String id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, values.get(0).id);
        for (UnifiedProfile p : values) if (p.id.equals(id)) return p;
        setActive(context, values.get(0).id);
        return values.get(0);
    }

    public static synchronized UnifiedProfile add(Context context, String raw) throws Exception {
        UnifiedProfile next = UnifiedProfile.parse(raw);
        List<UnifiedProfile> values = all(context);
        values.add(next); save(context, values, next.id); return next;
    }

    public static synchronized void replaceRaw(Context context, String id, String raw) throws Exception {
        UnifiedProfile parsed = UnifiedProfile.parse(raw);
        List<UnifiedProfile> values = all(context);
        boolean found = false;
        for (int i=0;i<values.size();i++) if (values.get(i).id.equals(id)) {
            UnifiedProfile old = values.get(i);
            if (!old.protocol.equals(parsed.protocol)) throw new Exception("Обновление изменило тип профиля");
            values.set(i, new UnifiedProfile(old.id, old.name, old.protocol, parsed.raw, old.createdAt)); found = true; break;
        }
        if (!found) throw new Exception("Профиль не найден");
        save(context, values, id);
    }

    public static synchronized void delete(Context context, String id) throws Exception {
        List<UnifiedProfile> values = all(context);
        values.removeIf(p -> p.id.equals(id));
        String active = values.isEmpty() ? "" : values.get(0).id;
        save(context, values, active);
    }

    public static synchronized void setActive(Context context, String id) throws Exception {
        boolean found = false;
        for (UnifiedProfile p : all(context)) if (p.id.equals(id)) { found = true; break; }
        if (!found) throw new Exception("Профиль не найден");
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE, id).apply();
    }

    private static void save(Context context, List<UnifiedProfile> values, String active) throws Exception {
        JSONArray a = new JSONArray(); for (UnifiedProfile p : values) a.put(p.toJson());
        byte[] plain = a.toString().getBytes(StandardCharsets.UTF_8);
        if (plain.length > 4 * 1024 * 1024) throw new Exception("Хранилище профилей переполнено");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String data = Base64.encodeToString(cipher.doFinal(plain), Base64.NO_WRAP);
        if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(IV, iv).putString(DATA, data).putString(ACTIVE, active).commit())
            throw new Exception("Не удалось сохранить профили");
    }

    private static void migrate(Context context) {
        var prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.contains(DATA) || prefs.getBoolean("migration_done", false)) return;
        try {
            String old = SecretStore.load(context);
            UnifiedProfile p = UnifiedProfile.parse(old);
            ArrayList<UnifiedProfile> list = new ArrayList<>(); list.add(p); save(context, list, p.id);
        } catch (Exception ignored) {
            prefs.edit().putBoolean("migration_done", true).apply();
        }
    }
}
