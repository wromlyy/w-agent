package dev.wagent;
import org.json.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Provider adapters share an internal OpenAI-style message/tool representation. */
public final class Models {
    public static JSONObject complete(JSONObject p,JSONArray messages,JSONArray tools,Net.Cancel cancel) throws Exception {
        String type=p.optString("type","compatible"),base=p.getString("base"),key=p.optString("key"),model=p.getString("model");
        if(model.trim().isEmpty())throw new IllegalArgumentException("Укажите модель в настройках подключения");
        if(p.optBoolean("freeOnly")&&(!"compatible".equals(type)||!"https://openrouter.ai/api/v1".equals(base.replaceAll("/+$",""))))throw new IllegalArgumentException("Бесплатное подключение использует OpenRouter и OpenAI-совместимый формат");
        if("anthropic".equals(type))return anthropic(base,key,model,messages,tools,cancel);
        if("gemini".equals(type))return gemini(base,key,model,messages,tools,cancel);
        JSONArray compatibleMessages=new JSONArray(messages.toString());
        for(int i=0;i<compatibleMessages.length();i++){
            JSONObject message=compatibleMessages.getJSONObject(i);JSONArray parts=message.optJSONArray("content");
            if(parts==null)continue;
            boolean textOnly=true;StringBuilder text=new StringBuilder();
            for(int j=0;j<parts.length();j++){JSONObject part=parts.getJSONObject(j);if(!"text".equals(part.optString("type"))){textOnly=false;break;}if(text.length()>0)text.append('\n');text.append(part.optString("text"));}
            if(textOnly)message.put("content",text.toString());
        }
        JSONObject request=new JSONObject().put("model",model).put("messages",compatibleMessages);
        if(tools.length()>0)request.put("tools",tools).put("parallel_tool_calls",false);
        if(p.optBoolean("freeOnly")) {
            if(!"https://openrouter.ai/api/v1".equals(base.replaceAll("/+$","")))throw new IllegalArgumentException("Бесплатное подключение использует OpenRouter");
            JSONObject available=findFree(model,freeModels(cancel));
            if(available==null)throw new IllegalStateException("Модель больше не указана как бесплатная. Обновите каталог или выберите openrouter/free.");
            if(tools.length()>0&&!available.optBoolean("tools"))throw new IllegalStateException("Эта бесплатная модель подходит только для чата. Выберите режим Чат в подключении.");
            request.put("provider",new JSONObject().put("max_price",new JSONObject().put("prompt",0).put("completion",0).put("request",0).put("image",0)));
        }
        JSONObject response=Net.request("POST",Net.join(base,"chat/completions"),Net.bearer(key),request.toString(),cancel).json();
        JSONArray choices=response.optJSONArray("choices");
        if(choices==null||choices.length()==0)throw new IllegalStateException("Сервер не вернул ответ chat/completions. Проверьте протокол подключения.");
        return choices.getJSONObject(0).getJSONObject("message");
    }
    public static JSONArray freeModels(Net.Cancel cancel) throws Exception {
        JSONObject response=Net.request("GET","https://openrouter.ai/api/v1/models",null,null,cancel).json();
        return freeCatalog(response.getJSONArray("data"));
    }
    static JSONArray freeCatalog(JSONArray data) throws Exception {
        JSONArray out=new JSONArray();
        for(int i=0;i<data.length();i++){
            JSONObject m=data.getJSONObject(i),p=m.optJSONObject("pricing"),arch=m.optJSONObject("architecture");
            if(p==null||!p.has("prompt")||!p.has("completion"))continue;
            boolean zero=true;Iterator<String> fields=p.keys();
            while(fields.hasNext()){String field=fields.next();try{if(new java.math.BigDecimal(p.get(field).toString()).compareTo(java.math.BigDecimal.ZERO)!=0)zero=false;}catch(Exception e){zero=false;}}
            if(!zero)continue;
            JSONArray output=arch==null?null:arch.optJSONArray("output_modalities");
            if(output==null||!contains(output,"text"))continue;
            JSONArray params=m.optJSONArray("supported_parameters"),input=arch.optJSONArray("input_modalities");
            out.put(new JSONObject().put("id",m.getString("id")).put("name",m.optString("name",m.getString("id")))
                .put("tools",contains(params,"tools")).put("vision",contains(input,"image")).put("context",m.optInt("context_length",0)));
        }
        return out;
    }
    static JSONObject findFree(String id,JSONArray catalog) throws Exception {for(int i=0;i<catalog.length();i++)if(id.equals(catalog.getJSONObject(i).getString("id")))return catalog.getJSONObject(i);return null;}
    private static boolean contains(JSONArray a,String value){if(a!=null)for(int i=0;i<a.length();i++)if(value.equals(a.optString(i)))return true;return false;}
    public static JSONArray models(JSONObject p,Net.Cancel cancel) throws Exception {
        String type=p.optString("type"),url=Net.join(p.getString("base"),"models");Map<String,String> h=Net.bearer(p.optString("key"));
        if("anthropic".equals(type)){h=new HashMap<>();h.put("x-api-key",p.optString("key"));h.put("anthropic-version","2023-06-01");}
        if("gemini".equals(type)){h=new HashMap<>();h.put("x-goog-api-key",p.optString("key"));}
        JSONObject r=Net.request("GET",url,h,null,cancel).json();JSONArray data=r.optJSONArray("data");if(data==null)data=r.optJSONArray("models");JSONArray out=new JSONArray();
        if(data!=null)for(int i=0;i<data.length();i++){JSONObject m=data.getJSONObject(i);out.put(m.optString("id",m.optString("name")).replaceFirst("^models/",""));}
        return out;
    }
    private static JSONObject anthropic(String base,String key,String model,JSONArray messages,JSONArray tools,Net.Cancel cancel) throws Exception {
        JSONArray ms=new JSONArray(),ts=new JSONArray();StringBuilder system=new StringBuilder();
        for(int i=0;i<tools.length();i++){JSONObject f=tools.getJSONObject(i).getJSONObject("function");ts.put(new JSONObject().put("name",f.getString("name")).put("description",f.optString("description")).put("input_schema",f.getJSONObject("parameters")));}
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.getJSONObject(i);String role=m.getString("role");
            if("system".equals(role)){system.append(m.optString("content"));continue;}
            JSONArray content=new JSONArray();
            if("tool".equals(role)){role="user";content.put(new JSONObject().put("type","tool_result").put("tool_use_id",m.getString("tool_call_id")).put("content",m.getString("content")));}
            else {
                Object raw=m.opt("content");
                if(raw instanceof JSONArray){JSONArray parts=(JSONArray)raw;for(int j=0;j<parts.length();j++){JSONObject part=parts.getJSONObject(j);String t=part.optString("type");
                    if("text".equals(t))content.put(part);
                    else if("image_url".equals(t)){String data=part.getJSONObject("image_url").getString("url");content.put(new JSONObject().put("type","image").put("source",source(data)));}
                    else if("file".equals(t)){JSONObject file=part.getJSONObject("file");content.put(new JSONObject().put("type","document").put("source",source(file.getString("file_data"))).put("title",file.getString("filename")));}
                }}else if(raw instanceof String&&!((String)raw).trim().isEmpty())content.put(new JSONObject().put("type","text").put("text",raw));
                JSONArray calls=m.optJSONArray("tool_calls");if(calls!=null)for(int j=0;j<calls.length();j++){JSONObject c=calls.getJSONObject(j),f=c.getJSONObject("function");content.put(new JSONObject().put("type","tool_use").put("id",c.getString("id")).put("name",f.getString("name")).put("input",new JSONObject(f.getString("arguments"))));}
            }
            if(content.length()>0)ms.put(new JSONObject().put("role",role).put("content",content));
        }
        Map<String,String> h=new HashMap<>();h.put("x-api-key",key);h.put("anthropic-version","2023-06-01");
        JSONObject request=new JSONObject().put("model",model).put("max_tokens",4096).put("system",system.toString()).put("messages",ms);
        if(ts.length()>0)request.put("tools",ts);
        JSONObject r=Net.request("POST",Net.join(base,"messages"),h,request.toString(),cancel).json();
        JSONArray calls=new JSONArray(),content=r.getJSONArray("content");StringBuilder text=new StringBuilder();
        for(int i=0;i<content.length();i++){JSONObject b=content.getJSONObject(i);if("text".equals(b.optString("type")))text.append(b.optString("text"));if("tool_use".equals(b.optString("type")))calls.put(call(b.getString("id"),b.getString("name"),b.getJSONObject("input")));}
        return new JSONObject().put("role","assistant").put("content",text.toString()).put("tool_calls",calls);
    }
    private static JSONObject source(String uri) throws Exception {
        int split=uri.indexOf(',');if(split<0||!uri.startsWith("data:"))throw new IllegalArgumentException("Неподдерживаемый формат вложения");
        return new JSONObject().put("type","base64").put("media_type",uri.substring(5,uri.indexOf(';'))).put("data",uri.substring(split+1));
    }
    private static JSONObject gemini(String base,String key,String model,JSONArray messages,JSONArray tools,Net.Cancel cancel) throws Exception {
        JSONArray contents=new JSONArray(),decl=new JSONArray();StringBuilder system=new StringBuilder();Map<String,String> callNames=new HashMap<>();
        for(int i=0;i<tools.length();i++){JSONObject f=tools.getJSONObject(i).getJSONObject("function");decl.put(new JSONObject().put("name",f.getString("name")).put("description",f.optString("description")).put("parameters",f.getJSONObject("parameters")));}
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.getJSONObject(i);String role=m.getString("role");if("system".equals(role)){system.append(m.optString("content"));continue;}
            JSONArray parts=new JSONArray();
            if("tool".equals(role)){role="user";parts.put(new JSONObject().put("functionResponse",new JSONObject().put("name",callNames.get(m.getString("tool_call_id"))).put("response",new JSONObject().put("result",m.getString("content")))));}
            else {
                Object raw=m.opt("content");
                if(raw instanceof JSONArray){JSONArray ps=(JSONArray)raw;for(int j=0;j<ps.length();j++){JSONObject part=ps.getJSONObject(j);String t=part.optString("type");
                    if("text".equals(t))parts.put(new JSONObject().put("text",part.getString("text")));
                    else {String data="image_url".equals(t)?part.getJSONObject("image_url").getString("url"):part.getJSONObject("file").getString("file_data");JSONObject src=source(data);parts.put(new JSONObject().put("inlineData",new JSONObject().put("mimeType",src.getString("media_type")).put("data",src.getString("data"))));}
                }}else if(raw instanceof String&&!((String)raw).trim().isEmpty())parts.put(new JSONObject().put("text",raw));
                JSONArray calls=m.optJSONArray("tool_calls");if(calls!=null)for(int j=0;j<calls.length();j++){JSONObject c=calls.getJSONObject(j),f=c.getJSONObject("function");callNames.put(c.getString("id"),f.getString("name"));JSONObject part=new JSONObject().put("functionCall",new JSONObject().put("name",f.getString("name")).put("args",new JSONObject(f.getString("arguments"))));if(c.has("thoughtSignature"))part.put("thoughtSignature",c.getString("thoughtSignature"));parts.put(part);}
            }
            if(parts.length()>0)contents.put(new JSONObject().put("role","assistant".equals(role)?"model":role).put("parts",parts));
        }
        JSONObject req=new JSONObject().put("contents",contents).put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system.toString())))).put("tools",new JSONArray().put(new JSONObject().put("functionDeclarations",decl)));
        if(decl.length()==0)req.remove("tools");
        Map<String,String> h=new HashMap<>();h.put("x-goog-api-key",key);
        String name=URLEncoder.encode(model,"UTF-8").replace("+","%20");
        JSONObject r=Net.request("POST",Net.join(base,"models/"+name+":generateContent"),h,req.toString(),cancel).json();
        JSONArray candidates=r.optJSONArray("candidates");if(candidates==null||candidates.length()==0)throw new IllegalStateException("Gemini не вернул ответ. Проверьте модель и ограничения аккаунта.");
        JSONArray parts=candidates.getJSONObject(0).getJSONObject("content").getJSONArray("parts"),calls=new JSONArray();StringBuilder text=new StringBuilder();
        for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(part.has("text")&&!part.optBoolean("thought"))text.append(part.getString("text"));JSONObject f=part.optJSONObject("functionCall");if(f!=null){JSONObject c=call("call_"+UUID.randomUUID().toString().replace("-",""),f.getString("name"),f.optJSONObject("args")==null?new JSONObject():f.getJSONObject("args"));if(part.has("thoughtSignature"))c.put("thoughtSignature",part.getString("thoughtSignature"));calls.put(c);}}
        return new JSONObject().put("role","assistant").put("content",text.toString()).put("tool_calls",calls);
    }
    private static JSONObject call(String id,String name,JSONObject args) throws Exception {return new JSONObject().put("id",id).put("type","function").put("function",new JSONObject().put("name",name).put("arguments",args.toString()));}
}
