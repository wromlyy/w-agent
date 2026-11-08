package dev.wagent;

import org.json.*;
import java.util.*;

/** Enforced independently of model instructions. No Android dependencies. */
public final class RunGuard {
    public static final class Limit extends Exception { public Limit(String message){super(message);} }
    private final long deadline;
    private final int maxActions,maxTokens;
    private int actions,tokens;
    private final Map<String,Integer> repeats=new HashMap<>();
    public RunGuard(int actions,int seconds,int tokens,long now){
        maxActions=Math.max(1,Math.min(200,actions));maxTokens=Math.max(1024,Math.min(500000,tokens));
        deadline=now+Math.max(10,Math.min(3600,seconds))*1000L;
    }
    public void check(long now)throws Limit{if(now>=deadline)throw new Limit("Достигнут лимит времени задачи");if(tokens>=maxTokens)throw new Limit("Достигнут бюджет токенов задачи");}
    public void account(int count){tokens+=Math.max(0,count);}
    public int remaining(){return Math.max(0,maxTokens-tokens);}
    public int used(){return tokens;}
    public int actions(){return actions;}
    public boolean expired(long now){return now>=deadline;}
    public String action(String name,JSONObject args)throws Exception{
        if(actions>=maxActions)throw new Limit("Достигнут лимит действий задачи");
        String signature=name+":"+canonical(args);int count=repeats.getOrDefault(signature,0)+1;repeats.put(signature,count);
        if(count>2)return "Повтор заблокирован: это действие уже выполнено дважды без подтверждённого прогресса. Прочитай результат, выбери другой способ или объясни препятствие. Не повторяй отклонённые пользователем действия.";
        actions++;return null;
    }
    public void progress(){repeats.clear();}
    static String canonical(Object value)throws JSONException{
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;List<String> keys=new ArrayList<>();o.keys().forEachRemaining(keys::add);Collections.sort(keys);StringBuilder s=new StringBuilder("{");for(String key:keys)s.append(JSONObject.quote(key)).append(':').append(canonical(o.get(key))).append(',');return s.append('}').toString();}
        if(value instanceof JSONArray){StringBuilder s=new StringBuilder("[");JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)s.append(canonical(a.get(i))).append(',');return s.append(']').toString();}
        return String.valueOf(value);
    }
    public static int estimate(JSONArray messages){return Math.max(1,(int)Math.min(Integer.MAX_VALUE,(messages.toString().length()+2L)/3));}
}
