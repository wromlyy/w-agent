package dev.wagent;
import org.json.*;
import java.util.*;
import java.util.function.Consumer;

/** Reassembles streamed tool arguments before they can be executed. */
final class ModelStream {
    private final String protocol;
    private final Consumer<String> text;
    private final StringBuilder content=new StringBuilder();
    private final TreeMap<Integer,JSONObject> calls=new TreeMap<>(),blocks=new TreeMap<>();
    private JSONObject response,usage;
    private boolean finished;
    ModelStream(String protocol,Consumer<String> text){this.protocol=protocol;this.text=text;}
    void accept(String data)throws Exception{
        if("[DONE]".equals(data)){finished=true;return;}
        JSONObject e=new JSONObject(data);if(e.has("error"))throw new IllegalStateException("Ошибка потока модели: "+e.get("error"));
        if("responses".equals(protocol)){
            String type=e.optString("type");if("response.output_text.delta".equals(type))append(e.optString("delta"));
            if("response.completed".equals(type)){response=e.getJSONObject("response");finished=true;}
            if("response.failed".equals(type)||"response.incomplete".equals(type))throw new IllegalStateException("Ответ Responses не завершён: "+e.optJSONObject("response"));return;
        }
        if("anthropic".equals(protocol)){
            String type=e.optString("type");int i=e.optInt("index");
            if("message_start".equals(type))usage=e.getJSONObject("message").optJSONObject("usage");
            if("content_block_start".equals(type)){JSONObject b=e.getJSONObject("content_block");blocks.put(i,b);if("tool_use".equals(b.optString("type")))b.put("_json","");}
            if("content_block_delta".equals(type)){JSONObject d=e.getJSONObject("delta"),b=blocks.get(i);if(b==null)throw new IllegalStateException("Отсутствует начало блока потока");String kind=d.optString("type");
                if("text_delta".equals(kind)){append(d.optString("text"));b.put("text",b.optString("text")+d.optString("text"));}
                if("input_json_delta".equals(kind))b.put("_json",b.optString("_json")+d.optString("partial_json"));
                if("thinking_delta".equals(kind))b.put("thinking",b.optString("thinking")+d.optString("thinking"));
                if("signature_delta".equals(kind))b.put("signature",b.optString("signature")+d.optString("signature"));
            }
            if("message_delta".equals(type)){JSONObject u=e.optJSONObject("usage");if(u!=null){if(usage==null)usage=new JSONObject();Iterator<String> k=u.keys();while(k.hasNext()){String key=k.next();usage.put(key,u.get(key));}}}
            if("message_stop".equals(type))finished=true;return;
        }
        if(e.has("usage")&&!e.isNull("usage"))usage=e.getJSONObject("usage");JSONArray choices=e.optJSONArray("choices");if(choices==null||choices.length()==0)return;
        JSONObject choice=choices.getJSONObject(0),d=choice.optJSONObject("delta");if(choice.has("finish_reason")&&!choice.isNull("finish_reason")){String reason=choice.optString("finish_reason");if("length".equals(reason))throw new IllegalStateException("Модель достигла лимита ответа. Увеличьте бюджет.");finished=true;}
        if(d==null)return;if(!d.isNull("content"))append(d.optString("content"));JSONArray tools=d.optJSONArray("tool_calls");
        if(tools!=null)for(int j=0;j<tools.length();j++){JSONObject t=tools.getJSONObject(j);int i=t.getInt("index");JSONObject c=calls.get(i);if(c==null){c=new JSONObject().put("id","").put("type","function").put("function",new JSONObject().put("name","").put("arguments",""));calls.put(i,c);}if(t.has("id"))c.put("id",t.getString("id"));JSONObject f=t.optJSONObject("function"),cf=c.getJSONObject("function");if(f!=null){if(f.has("name"))cf.put("name",cf.getString("name")+f.getString("name"));if(f.has("arguments"))cf.put("arguments",cf.getString("arguments")+f.getString("arguments"));}}
    }
    private void append(String delta){if(delta.isEmpty())return;content.append(delta);if(text!=null)text.accept(delta);}
    JSONObject finish()throws Exception{
        if(!finished)throw new IllegalStateException("Поток оборвался до завершения. Частичные команды не выполнялись.");
        if("responses".equals(protocol))return response;
        if("anthropic".equals(protocol)){JSONArray a=new JSONArray();for(JSONObject b:blocks.values()){if(b.has("_json")){String json=b.getString("_json");if(!json.isEmpty())b.put("input",new JSONObject(json));b.remove("_json");}a.put(b);}return new JSONObject().put("content",a).put("usage",usage);}
        JSONArray a=new JSONArray();for(JSONObject c:calls.values()){new JSONObject(c.getJSONObject("function").getString("arguments"));a.put(c);}
        return new JSONObject().put("choices",new JSONArray().put(new JSONObject().put("message",new JSONObject().put("role","assistant").put("content",content.toString()).put("tool_calls",a)))).put("usage",usage);
    }
}
