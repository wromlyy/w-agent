package dev.wagent;

import android.app.*;
import android.content.*;
import android.os.IBinder;

/** Keeps a user-started interactive shell visible while switching applications. */
public final class TerminalService extends Service {
    @Override public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("terminal","Терминал",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent!=null&&"stop".equals(intent.getAction())){WApp app=(WApp)getApplication();if(app.pty!=null)app.pty.stop();stopSelf();return START_NOT_STICKY;}
        PendingIntent open=PendingIntent.getActivity(this,5,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,6,new Intent(this,TerminalService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        startForeground(8,new Notification.Builder(this,"terminal").setSmallIcon(R.drawable.ic_launcher).setContentTitle("W Agent · Linux-терминал").setContentText("Работает интерактивная сессия").setContentIntent(open).addAction(new Notification.Action.Builder(null,"Остановить",stop).build()).setOngoing(true).build());return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
