package dev.wagent;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.net.URLEncoder;

/** Documented OpenRouter PKCE flow with a user-pasted, single-use authorization code. */
public final class OpenRouterAuth {
    public static JSONObject begin(long now) throws Exception {
        byte[] bytes=new byte[48];new SecureRandom().nextBytes(bytes);
        String verifier=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return new JSONObject().put("verifier",verifier).put("created",now);
    }
    static String challenge(String verifier) throws Exception {return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}
    public static String url(JSONObject flow) throws Exception {
        return "https://openrouter.ai/auth?code_challenge="+challenge(flow.getString("verifier"))+"&code_challenge_method=S256&key_label="+URLEncoder.encode("W Agent","UTF-8");
    }
    public static String exchange(JSONObject flow,String code,long now,Net.Cancel cancel) throws Exception {
        if(flow==null||now<flow.optLong("created")||now-flow.optLong("created")>600000)throw new IllegalArgumentException("Время входа истекло. Начните вход заново.");
        code=code.trim();if(code.isEmpty()||code.length()>2048)throw new IllegalArgumentException("Вставьте одноразовый код со страницы OpenRouter");
        JSONObject body=new JSONObject().put("code",code).put("code_verifier",flow.getString("verifier")).put("code_challenge_method","S256");
        JSONObject reply=Net.request("POST","https://openrouter.ai/api/v1/auth/keys",null,body.toString(),cancel).json();
        String key=reply.optString("key");if(key.trim().isEmpty())throw new IllegalStateException("OpenRouter не вернул API-ключ");return key;
    }
}
