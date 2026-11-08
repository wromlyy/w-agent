package dev.wagent;

import android.accessibilityservice.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import org.json.*;

/** Separates the permission switch from the service's actual Android connection. */
public final class DeviceStatus {
    public static boolean enabled(Context context){
        ComponentName expected=new ComponentName(context,DeviceService.class);
        String raw=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(raw!=null)for(String value:raw.split(":")){ComponentName name=ComponentName.unflattenFromString(value);if(expected.equals(name))return true;}
        AccessibilityManager manager=context.getSystemService(AccessibilityManager.class);
        if(manager!=null)for(AccessibilityServiceInfo info:manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)){
            if(info.getResolveInfo()!=null){android.content.pm.ServiceInfo s=info.getResolveInfo().serviceInfo;if(s!=null&&expected.equals(new ComponentName(s.packageName,s.name)))return true;}
        }
        return false;
    }
    public static JSONObject get(Context context)throws Exception{
        boolean enabled=enabled(context),connected=DeviceService.live!=null;
        android.content.SharedPreferences prefs=context.getSharedPreferences("device-health",0);
        return new JSONObject().put("enabled",enabled).put("connected",connected).put("state",connected?"connected":enabled?"waiting":"disabled")
            .put("message",connected?"Служба подключена":enabled?"Включена в Android, ожидает подключения":"Выключена в Android")
            .put("lastError",prefs.getString("error","")).put("lastConnected",prefs.getLong("connected",0))
            .put("manufacturer",Build.MANUFACTURER).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE);
    }
    public static void record(Context c,String event,Throwable error){
        android.content.SharedPreferences.Editor e=c.getSharedPreferences("device-health",0).edit().putString("event",event).putLong("eventTime",System.currentTimeMillis());
        if("connected".equals(event))e.putLong("connected",System.currentTimeMillis()).remove("error");
        if(error!=null){String detail=error.getClass().getSimpleName();StackTraceElement[] trace=error.getStackTrace();if(trace.length>0)detail+=" · "+trace[0].getClassName()+":"+trace[0].getLineNumber();e.putString("error",detail);}
        e.apply();
    }
}
