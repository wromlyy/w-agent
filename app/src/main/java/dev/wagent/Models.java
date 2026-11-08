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
        if("responses".equals(type)||("api.openai.com".equals(java.net.URI.create(base).getHost())&&model.startsWith("gpt-6")))return responses(p,messages,tools,cancel);
        if("anthropic".equals(type))return anthropic(p,base,key,model,messages,tools,cancel);
        if("gemini".equals(type))return gemini(p,base,key,model,messages,tools,cancel);
        JSONArray compatibleMessages=new JSONArray(messages.toString());
        for(int i=0;i<compatibleMessages.length();i++){
            JSONObject message=compatibleMessages.getJSONObject(i);JSONArray parts=message.optJSONArray("content");
            if(parts==null)continue;
            boolean textOnly=true;StringBuilder text=new StringBuilder();
            for(int j=0;j<parts.length();j++){JSONObject part=parts.getJSONObject(j);if(!"text".equals(part.optString("type"))){textOnly=false;break;}if(text.length()>0)text.append('\n');text.append(part.optString("text"));}
            if(textOnly)message.put("content",text.toString());
        }
        JSONObject request=new JSONObject().put("model",model).put("messages",compatibleMessages);
        for(int i=0;i<compatibleMessages.length();i++){compatibleMessages.getJSONObject(i).remove("_usage");compatibleMessages.getJSONObject(i).remove("_responseItems");compatibleMessages.getJSONObject(i).remove("_anthropicContent");}
        applyReasoning(p,request);if(p.has("outputLimit"))request.put("max_completion_tokens",p.getInt("outputLimit"));
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
        return choices.getJSONObject(0).getJSONObject("message").put("_usage",response.optJSONObject("usage"));
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
    static String effort(JSONObject p)throws Exception{
        String value=p.optString("effort","auto");if(!Arrays.asList("auto","minimal","low","medium","high","xhigh","max").contains(value))throw new IllegalArgumentException("Неизвестный уровень рассуждений");return value;
    }
    static void applyReasoning(JSONObject profile,JSONObject request)throws Exception{
        String mode=profile.optString("thinking","auto"),effort=effort(profile);if("auto".equals(mode)&&"auto".equals(effort))return;
        String host=java.net.URI.create(profile.getString("base")).getHost();
        if("openrouter.ai".equals(host)){JSONObject r=new JSONObject().put("exclude",false);if("off".equals(mode))r.put("enabled",false);else{r.put("enabled",true);if(!"auto".equals(effort))r.put("effort",effort);}request.put("reasoning",r);}
        else if("api.deepseek.com".equals(host)){request.put("thinking",new JSONObject().put("type","off".equals(mode)?"disabled":"enabled"));if(!"auto".equals(effort)&&!"off".equals(mode))request.put("reasoning_effort",effort);}
        else if("off".equals(mode))request.put("reasoning_effort","none");
        else if(!"auto".equals(effort))request.put("reasoning_effort",effort);
        else if("on".equals(mode))request.put("reasoning_effort","medium");
    }
    private static int thinkingBudget(JSONObject p)throws Exception{return switch(effort(p)){case "minimal","low"->1024;case "high"->8192;case "xhigh","max"->16384;default->4096;};}
    private static JSONObject responses(JSONObject p,JSONArray messages,JSONArray tools,Net.Cancel cancel)throws Exception{
        JSONArray input=new JSONArray(),definitions=new JSONArray();
        for(int i=0;i<tools.length();i++){JSONObject f=tools.getJSONObject(i).getJSONObject("function");definitions.put(new JSONObject().put("type","function").put("name",f.getString("name")).put("description",f.optString("description")).put("parameters",f.getJSONObject("parameters")).put("strict",false));}
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.getJSONObject(i);String role=m.getString("role");
            if("tool".equals(role)){input.put(new JSONObject().put("type","function_call_output").put("call_id",m.getString("tool_call_id")).put("output",m.getString("content")));continue;}
            JSONArray saved=m.optJSONArray("_responseItems");if(saved!=null){for(int j=0;j<saved.length();j++)input.put(saved.get(j));continue;}
            Object raw=m.opt("content");if(raw instanceof JSONArray){JSONArray parts=(JSONArray)raw,converted=new JSONArray();for(int j=0;j<parts.length();j++){JSONObject part=parts.getJSONObject(j);String type=part.optString("type");
                if("text".equals(type))converted.put(new JSONObject().put("type","input_text").put("text",part.getString("text")));
                else if("image_url".equals(type))converted.put(new JSONObject().put("type","input_image").put("image_url",part.getJSONObject("image_url").getString("url")));
                else if("file".equals(type)){JSONObject f=part.getJSONObject("file");converted.put(new JSONObject().put("type","input_file").put("filename",f.getString("filename")).put("file_data",f.getString("file_data")));}
            }input.put(new JSONObject().put("role",role).put("content",converted));}
            else input.put(new JSONObject().put("role",role).put("content",raw instanceof String?raw:""));
        }
        JSONObject req=new JSONObject().put("model",p.getString("model")).put("input",input).put("store",false).put("include",new JSONArray().put("reasoning.encrypted_content"));
        if(p.has("outputLimit"))req.put("max_output_tokens",p.getInt("outputLimit"));
        if(definitions.length()>0)req.put("tools",definitions).put("parallel_tool_calls",false);
        String effort=effort(p),mode=p.optString("thinking","auto");if(!"auto".equals(effort)||!"auto".equals(mode)){JSONObject reasoning=new JSONObject();if("off".equals(mode))reasoning.put("effort","none");else if(!"auto".equals(effort))reasoning.put("effort",effort);else reasoning.put("effort","medium");req.put("reasoning",reasoning);}
        JSONObject r=Net.request("POST",Net.join(p.getString("base"),"responses"),Net.bearer(p.optString("key")),req.toString(),cancel).json();
        JSONArray output=r.optJSONArray("output");if(output==null)throw new IllegalStateException("Responses API не вернул output");
        JSONArray calls=new JSONArray();StringBuilder text=new StringBuilder();
        for(int i=0;i<output.length();i++){JSONObject item=output.getJSONObject(i);if("function_call".equals(item.optString("type")))calls.put(call(item.getString("call_id"),item.getString("name"),new JSONObject(item.getString("arguments"))));
            JSONArray content=item.optJSONArray("content");if(content!=null)for(int j=0;j<content.length();j++){JSONObject part=content.getJSONObject(j);if("output_text".equals(part.optString("type")))text.append(part.optString("text"));}
        }
        return new JSONObject().put("role","assistant").put("content",text.toString()).put("tool_calls",calls).put("_responseItems",output).put("_usage",r.optJSONObject("usage"));
    }
    private static JSONObject anthropic(JSONObject profile,String base,String key,String model,JSONArray messages,JSONArray tools,Net.Cancel cancel) throws Exception {
        JSONArray ms=new JSONArray(),ts=new JSONArray();StringBuilder system=new StringBuilder();
        for(int i=0;i<tools.length();i++){JSONObject f=tools.getJSONObject(i).getJSONObject("function");ts.put(new JSONObject().put("name",f.getString("name")).put("description",f.optString("description")).put("input_schema",f.getJSONObject("parameters")));}
        for(int i=0;i<messages.length();i++){
            JSONObject m=messages.getJSONObject(i);String role=m.getString("role");
            if("system".equals(role)){system.append(m.optString("content"));continue;}
            JSONArray content=new JSONArray();
            if("tool".equals(role)){role="user";content.put(new JSONObject().put("type","tool_result").put("tool_use_id",m.getString("tool_call_id")).put("content",m.getString("content")));}
            else if(m.has("_anthropicContent"))content=m.getJSONArray("_anthropicContent");
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
        String mode=profile.optString("thinking","auto");if("off".equals(mode))request.put("thinking",new JSONObject().put("type","disabled"));else if("on".equals(mode)||!"auto".equals(effort(profile))){int budget=thinkingBudget(profile);request.put("thinking",new JSONObject().put("type","enabled").put("budget_tokens",budget)).put("max_tokens",budget+4096);}
        if(profile.has("outputLimit")){int cap=profile.getInt("outputLimit");JSONObject thinking=request.optJSONObject("thinking");if(thinking!=null&&"enabled".equals(thinking.optString("type"))&&thinking.optInt("budget_tokens")>=cap)throw new IllegalArgumentException("Бюджет задачи меньше бюджета размышлений Anthropic. Увеличьте лимит токенов или выберите Авто.");request.put("max_tokens",Math.min(request.getInt("max_tokens"),cap));}
        if(ts.length()>0)request.put("tools",ts);
        JSONObject r=Net.request("POST",Net.join(base,"messages"),h,request.toString(),cancel).json();
        JSONArray calls=new JSONArray(),content=r.getJSONArray("content");StringBuilder text=new StringBuilder();
        for(int i=0;i<content.length();i++){JSONObject b=content.getJSONObject(i);if("text".equals(b.optString("type")))text.append(b.optString("text"));if("tool_use".equals(b.optString("type")))calls.put(call(b.getString("id"),b.getString("name"),b.getJSONObject("input")));}
        return new JSONObject().put("role","assistant").put("content",text.toString()).put("tool_calls",calls).put("_anthropicContent",content).put("_usage",r.optJSONObject("usage"));
    }
    private static JSONObject source(String uri) throws Exception {
        int split=uri.indexOf(',');if(split<0||!uri.startsWith("data:"))throw new IllegalArgumentException("Неподдерживаемый формат вложения");
        return new JSONObject().put("type","base64").put("media_type",uri.substring(5,uri.indexOf(';'))).put("data",uri.substring(split+1));
    }
    private static JSONObject gemini(JSONObject profile,String base,String key,String model,JSONArray messages,JSONArray tools,Net.Cancel cancel) throws Exception {
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
        String mode=profile.optString("thinking","auto"),effort=effort(profile);
        if(!"auto".equals(mode)||!"auto".equals(effort)){
            JSONObject thinking=new JSONObject();
            if(model.startsWith("gemini-2"))thinking.put("thinkingBudget","off".equals(mode)?0:thinkingBudget(profile));
            else{if("off".equals(mode))throw new IllegalArgumentException("У этой версии Gemini нельзя отключить рассуждения этим параметром. Выберите Авто или Low.");thinking.put("thinkingLevel","auto".equals(effort)?"medium":effort);}
            req.put("generationConfig",new JSONObject().put("thinkingConfig",thinking));
        }
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
