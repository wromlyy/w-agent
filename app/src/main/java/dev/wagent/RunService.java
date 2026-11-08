package dev.wagent;
import android.app.*;
import android.content.*;
import android.os.*;
public class RunService extends Service {
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("agent","Выполнение задачи",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&"stop".equals(intent.getAction())){((WApp)getApplication()).agent.stop();stopSelf();return START_NOT_STICKY;}
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RunService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"agent").setSmallIcon(dev.wagent.R.drawable.ic_launcher).setContentTitle("W Agent выполняет задачу").setContentText("Нажмите, чтобы открыть чат").setContentIntent(open).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).setOngoing(true).build();
        startForeground(7,n); return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){super.onDestroy();}
}
