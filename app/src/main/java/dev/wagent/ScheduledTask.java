package dev.wagent;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.work.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Scheduled work posts a user-visible launch reminder; it never silently drives the phone. */
public class ScheduledTask extends Worker {
    public ScheduledTask(Context context,WorkerParameters params){super(context,params);}
    @Override public Result doWork(){
        WApp app=(WApp)getApplicationContext();String id=getInputData().getString("id");try{
            JSONArray all=app.vault.get().optJSONArray("schedules");if(all==null)return Result.success();JSONObject task=null;for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))task=all.getJSONObject(i);if(task==null||!task.optBoolean("enabled"))return Result.success();
            if(Build.VERSION.SDK_INT>=33&&app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return Result.failure();
            NotificationManager manager=app.getSystemService(NotificationManager.class);manager.createNotificationChannel(new NotificationChannel("scheduled","Запланированные задачи",NotificationManager.IMPORTANCE_DEFAULT));
            Intent open=new Intent(app,MainActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,task.getString("task")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pending=PendingIntent.getActivity(app,id.hashCode(),open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            manager.notify(id.hashCode(),new Notification.Builder(app,"scheduled").setSmallIcon(dev.wagent.R.drawable.ic_launcher).setContentTitle("W Agent: запланированная задача").setContentText("Нажмите, чтобы открыть и запустить задачу").setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build());return Result.success();
        }catch(Exception e){return Result.failure();}
    }
    static JSONObject schedule(WApp app,String task,long at,int interval)throws Exception{
        if(task.trim().isEmpty()||task.length()>20000)throw new IllegalArgumentException("Укажите задачу до 20 000 символов");if(interval!=0&&interval<15)throw new IllegalArgumentException("Повтор: минимум 15 минут");String id=UUID.randomUUID().toString();JSONObject item=new JSONObject().put("id",id).put("task",task).put("at",at).put("interval",interval).put("enabled",true);
        synchronized(app.vault){JSONArray all=app.vault.get().optJSONArray("schedules");if(all==null)all=new JSONArray();if(all.length()>=30)throw new IllegalArgumentException("Лимит 30 расписаний");all.put(item);app.vault.patch("schedules",all);}
        Data data=new Data.Builder().putString("id",id).build();long delay=Math.max(0,at-System.currentTimeMillis());WorkManager work=WorkManager.getInstance(app);
        if(interval==0)work.enqueueUniqueWork("w-task-"+id,ExistingWorkPolicy.REPLACE,new OneTimeWorkRequest.Builder(ScheduledTask.class).setInputData(data).setInitialDelay(delay,TimeUnit.MILLISECONDS).build());
        else work.enqueueUniquePeriodicWork("w-task-"+id,ExistingPeriodicWorkPolicy.REPLACE,new PeriodicWorkRequest.Builder(ScheduledTask.class,interval,TimeUnit.MINUTES).setInputData(data).setInitialDelay(delay,TimeUnit.MILLISECONDS).build());return item;
    }
    static void cancel(WApp app,String id)throws Exception{WorkManager.getInstance(app).cancelUniqueWork("w-task-"+id);synchronized(app.vault){JSONArray a=app.vault.get().optJSONArray("schedules");if(a!=null){for(int i=0;i<a.length();i++)if(id.equals(a.getJSONObject(i).optString("id"))){a.remove(i);break;}app.vault.patch("schedules",a);}}}
}
