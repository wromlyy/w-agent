package dev.wagent;
import org.json.*;
public final class AppPolicy {
    private static boolean has(JSONArray a,String pkg){if(a!=null)for(int i=0;i<a.length();i++)if(pkg.equals(a.optString(i)))return true;return false;}
    static void check(JSONObject settings,String pkg){if(pkg==null||pkg.equals("dev.wagent"))return;if(has(settings.optJSONArray("blockedApps"),pkg))throw new IllegalStateException("Доступ к приложению запрещён: "+pkg);JSONArray allow=settings.optJSONArray("allowedApps");if(allow!=null&&allow.length()>0&&!has(allow,pkg))throw new IllegalStateException("Приложение отсутствует в разрешённом списке: "+pkg);}
}
