package dev.wagent;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.*;

public class AgentTile extends TileService {
    @Override public void onStartListening(){Tile tile=getQsTile();if(tile!=null){Agent agent=((WApp)getApplication()).agent;tile.setState(agent.running()?Tile.STATE_ACTIVE:Tile.STATE_INACTIVE);tile.setLabel("W Agent");if(Build.VERSION.SDK_INT>=29)tile.setSubtitle(agent.running()?(agent.paused()?"Пауза":"Задача выполняется"):"Открыть помощника");tile.updateTile();}}
    @Override public void onClick(){Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,9,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));else startActivityAndCollapse(intent);}
}
