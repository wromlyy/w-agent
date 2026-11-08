package dev.wagent;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Portable configuration. Secrets require password encryption; device-specific grants never migrate. */
public final class Backups {
    private static byte[] random(int n){byte[] b=new byte[n];new SecureRandom().nextBytes(b);return b;}
    private static String b64(byte[] data){return Base64.getEncoder().encodeToString(data);}
    private static byte[] un64(String text){return Base64.getDecoder().decode(text);}
    private static javax.crypto.SecretKey key(String password,byte[] salt)throws Exception{
        if(password.length()<10||password.length()>1000)throw new IllegalArgumentException("Пароль резервной копии: 10–1000 символов");
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,150000,256);try{return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(),"AES");}finally{spec.clearPassword();}
    }
    public static JSONObject export(JSONObject state,boolean secrets,String password)throws Exception{
        JSONObject data=new JSONObject().put("app","W Agent").put("format",1);
        for(String name:new String[]{"profiles","services","skills","roles","prompts","activeProfile","activeRole","theme","fontSize","density","accent","amoled","materialYou","autoLocal","glass","glassOpacity","glassBlur","background","vision","agentMode","maxSteps","maxSeconds","maxTokens","doubleCheck","teamMode","blockedApps","allowedApps"})if(state.has(name))data.put(name,new JSONObject(state.toString()).get(name));
        if(!secrets){for(String group:new String[]{"profiles","services"}){JSONArray list=data.optJSONArray(group);if(list!=null)for(int i=0;i<list.length();i++){list.getJSONObject(i).remove("key");list.getJSONObject(i).remove("token");}}return new JSONObject().put("app","W Agent backup").put("encrypted",false).put("data",data);}
        byte[] salt=random(16);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(password,salt));cipher.updateAAD("W Agent backup v1".getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("app","W Agent backup").put("encrypted",true).put("format",1).put("salt",b64(salt)).put("iv",b64(cipher.getIV())).put("data",b64(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8))));
    }
    public static JSONObject decode(String raw,String password)throws Exception{
        if(raw.length()>3*1024*1024)throw new IllegalArgumentException("Резервная копия больше 3 МБ");JSONObject box=new JSONObject(raw);if(!"W Agent backup".equals(box.optString("app")))throw new IllegalArgumentException("Это не резервная копия W Agent");JSONObject data;
        if(box.optBoolean("encrypted")){byte[] salt=un64(box.getString("salt")),iv=un64(box.getString("iv"));if(salt.length!=16||iv.length!=12||box.optInt("format")!=1)throw new IllegalArgumentException("Неизвестный формат шифрования");Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(password,salt),new GCMParameterSpec(128,iv));cipher.updateAAD("W Agent backup v1".getBytes(StandardCharsets.UTF_8));try{data=new JSONObject(new String(cipher.doFinal(un64(box.getString("data"))),StandardCharsets.UTF_8));}catch(AEADBadTagException e){throw new IllegalArgumentException("Неверный пароль или повреждённая копия");}}
        else{data=box.getJSONObject("data");for(String group:new String[]{"profiles","services"}){JSONArray list=data.optJSONArray(group);if(list!=null)for(int i=0;i<list.length();i++){list.getJSONObject(i).remove("key");list.getJSONObject(i).remove("token");}}}
        if(data.optInt("format")!=1||!"W Agent".equals(data.optString("app")))throw new IllegalArgumentException("Неизвестная версия резервной копии");
        for(String group:new String[]{"profiles","services"}){JSONArray list=data.optJSONArray(group);if(list!=null)for(int i=0;i<list.length();i++){JSONObject item=list.getJSONObject(i);String address=item.optString("base",item.optString("url"));if(!address.startsWith("https://"))throw new IllegalArgumentException("Подключения в копии должны использовать HTTPS");}}
        if(data.has("roles"))Roles.validate(data.getJSONArray("roles"));return data;
    }
}
