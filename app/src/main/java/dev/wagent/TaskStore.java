package dev.wagent;
import org.json.*;
import java.util.*;

final class TaskStore {
    private final WApp app;
    TaskStore(WApp app){this.app=app;}
    JSONObject enqueue(String task)throws Exception{if(task.trim().isEmpty()||task.length()>20000)throw new IllegalArgumentException("Задача пуста или слишком длинная");synchronized(app.vault){JSONArray list=app.vault.get().optJSONArray("queue");if(list==null)list=new JSONArray();if(list.length()>=20)throw new IllegalArgumentException("В очереди уже 20 задач");JSONObject row=new JSONObject().put("id",UUID.randomUUID().toString()).put("task",task).put("time",System.currentTimeMillis());list.put(row);app.vault.patch("queue",list);return row;}}
    JSONObject take()throws Exception{synchronized(app.vault){JSONArray list=app.vault.get().optJSONArray("queue");if(list==null||list.length()==0)return null;JSONObject next=list.getJSONObject(0);list.remove(0);app.vault.patch("queue",list);return next;}}
    void remove(String id)throws Exception{synchronized(app.vault){JSONArray list=app.vault.get().optJSONArray("queue");if(list==null)return;for(int i=0;i<list.length();i++)if(id.equals(list.getJSONObject(i).getString("id"))){list.remove(i);break;}app.vault.patch("queue",list);}}
    JSONObject macro(String runId,String name)throws Exception{
        if(name.trim().isEmpty())throw new IllegalArgumentException("Укажите название сценария");JSONObject source=null;JSONArray history=app.vault.get().getJSONArray("history");for(int i=0;i<history.length();i++)if(runId.equals(history.getJSONObject(i).optString("id")))source=history.getJSONObject(i);
        if(source==null||!"done".equals(source.optString("status"))||source.optJSONArray("trace")==null||source.getJSONArray("trace").length()==0)throw new IllegalArgumentException("Нужна завершённая задача с успешными вызовами инструментов");
        JSONArray steps=source.getJSONArray("trace");for(int i=0;i<steps.length();i++)if(!steps.getJSONObject(i).optBoolean("ok"))throw new IllegalArgumentException("В цепочке есть непроверенный или неуспешный шаг; сценарий не сохранён");
        JSONObject result=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("steps",steps).put("source",runId);
        synchronized(app.vault){JSONArray list=app.vault.get().optJSONArray("macros");if(list==null)list=new JSONArray();if(list.length()>=30)throw new IllegalArgumentException("Лимит 30 сценариев");list.put(result);app.vault.patch("macros",list);}return result;
    }
}
