package dev.wagent;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** All connection secrets and local history are encrypted with an Android Keystore key. */
public final class Vault {
    private final Context context;
    private JSONObject state;
    public Vault(Context context) {
        this.context = context;
        try {
            String raw = context.getSharedPreferences("vault", 0).getString("data", "");
            if (raw.isEmpty()) state = defaults();
            else {
                JSONObject envelope = new JSONObject(raw);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)));
                state = new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"), Base64.NO_WRAP)), StandardCharsets.UTF_8));
            }
        } catch (Exception e) { throw new IllegalStateException("Не удалось открыть хранилище ключей. Очистите данные приложения, если Android Keystore был сброшен.", e); }
    }
    private javax.crypto.SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias("w-agent-v1")) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder("w-agent-v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            return gen.generateKey();
        }
        return (javax.crypto.SecretKey)ks.getKey("w-agent-v1", null);
    }
    private JSONObject defaults() throws JSONException {
        return new JSONObject().put("profiles", new JSONArray()).put("services", new JSONArray()).put("skills",new JSONArray()).put("history", new JSONArray())
            .put("activeProfile", "").put("vision", false).put("theme", "light").put("maxSteps", 30);
    }
    public synchronized JSONObject get() { try { return new JSONObject(state.toString()); } catch (Exception e) { throw new IllegalStateException(e); } }
    public synchronized void save(JSONObject next) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(next.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope = new JSONObject().put("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).put("data", Base64.encodeToString(encrypted, Base64.NO_WRAP));
        if (!context.getSharedPreferences("vault", 0).edit().putString("data", envelope.toString()).commit()) throw new IllegalStateException("Не удалось сохранить настройки");
        state = new JSONObject(next.toString());
    }
    public synchronized void updateSettings(JSONObject settings) throws Exception {
        JSONObject next = get();
        if(settings.has("roles"))Roles.validate(settings.getJSONArray("roles"));
        for (String k : new String[]{"profiles", "services", "skills", "activeProfile", "vision", "theme", "maxSteps", "maxSeconds", "maxTokens", "doubleCheck", "teamMode", "agentMode", "projectFolder", "roles", "activeRole", "searchService"}) if (settings.has(k)) next.put(k, settings.get(k));
        save(next);
    }
    public synchronized void patch(String key,Object value)throws Exception{save(get().put(key,value));}
    public synchronized void history(JSONObject entry) throws Exception {
        JSONObject next=get(); JSONArray history=next.getJSONArray("history"); history.put(entry);
        while(history.length()>60) history.remove(0);
        save(next.put("history",history));
    }
}
