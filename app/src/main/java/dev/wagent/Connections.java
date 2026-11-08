package dev.wagent;
import org.json.*;
import java.net.URI;
import java.util.*;

public final class Connections {
    private static final Map<String,String> sessions=new java.util.concurrent.ConcurrentHashMap<>();
    public static String execute(JSONObject service,JSONObject args,Net.Cancel cancel) throws Exception {
        if(!service.optBoolean("enabled",true))throw new IllegalArgumentException("Подключение выключено");
        if("mcp".equals(service.optString("type"))) {
            String method=args.optString("operation","tools/list");
            if(!Arrays.asList("tools/list","tools/call","resources/list","resources/read","prompts/list","prompts/get").contains(method))throw new IllegalArgumentException("Неизвестная операция MCP");
            JSONObject params=args.optJSONObject("params");if(params==null)params=new JSONObject();
            return rpc(service,method,params,cancel).toString();
        }
        String method=args.optString("method","GET").toUpperCase(Locale.ROOT);
        if(!Arrays.asList("GET","POST","PUT","PATCH","DELETE").contains(method))throw new IllegalArgumentException("Неизвестный HTTP-метод");
        String base=service.getString("url").replaceAll("/+$",""),path=args.optString("path","");
        if(path.contains(":")||path.contains("\\")||path.contains("..")||path.startsWith("//"))throw new IllegalArgumentException("Укажите относительный путь API");
        String url=Net.join(base,path);URI b=URI.create(base),u=URI.create(url);
        if(!Objects.equals(b.getHost(),u.getHost())||b.getPort()!=u.getPort())throw new IllegalArgumentException("Адрес выходит за пределы подключения");
        Map<String,String> h=Net.bearer(service.optString("token"));h.put("Accept","application/json");
        if("api.github.com".equals(b.getHost())){h.put("Accept","application/vnd.github+json");h.put("X-GitHub-Api-Version","2022-11-28");}
        String body="GET".equals(method)?null:args.optString("body","{}");
        Net.Reply r=Net.request(method,url,h,body,cancel);
        return r.text.length()>30000?r.text.substring(0,30000)+"\n[Ответ сокращён]":r.text;
    }
    private static JSONObject rpc(JSONObject s,String method,JSONObject params,Net.Cancel cancel) throws Exception {
        String id=s.getString("id");
        if(!sessions.containsKey(id)){
            JSONObject init=new JSONObject().put("protocolVersion","2025-03-26").put("capabilities",new JSONObject()).put("clientInfo",new JSONObject().put("name","W Agent").put("version","0.1.0"));
            JSONObject response=post(s,"initialize",init,cancel,false);
            if(response.has("error"))throw new IllegalStateException("MCP initialize: "+response.get("error"));
            if(!sessions.containsKey(id))sessions.put(id,"");
            post(s,"notifications/initialized",new JSONObject(),cancel,true);
        }
        JSONObject r=post(s,method,params,cancel,false);if(r.has("error"))throw new IllegalStateException("MCP: "+r.get("error"));
        return r.optJSONObject("result")==null?r:r.getJSONObject("result");
    }
    private static JSONObject post(JSONObject s,String method,JSONObject params,Net.Cancel cancel,boolean notification) throws Exception {
        Map<String,String> h=Net.bearer(s.optString("token"));h.put("Accept","application/json, text/event-stream");h.put("MCP-Protocol-Version","2025-03-26");
        String session=sessions.get(s.getString("id"));if(session!=null&&!session.isEmpty())h.put("Mcp-Session-Id",session);
        JSONObject req=new JSONObject().put("jsonrpc","2.0").put("method",method).put("params",params);if(!notification)req.put("id",UUID.randomUUID().toString());
        Net.Reply r=Net.request("POST",s.getString("url"),h,req.toString(),cancel);
        if(r.session!=null)sessions.put(s.getString("id"),r.session);
        if(r.text.trim().isEmpty())return new JSONObject();
        String text=r.text.trim();if(text.startsWith("{"))return new JSONObject(text);
        // Streamable HTTP may encode the JSON-RPC response as an SSE event.
        for(String event:text.split("\\r?\\n\\r?\\n")){
            StringBuilder data=new StringBuilder();for(String line:event.split("\\r?\\n"))if(line.startsWith("data:"))data.append(line.substring(5).trim());
            if(data.length()>0)try{JSONObject o=new JSONObject(data.toString());if(o.has("result")||o.has("error"))return o;}catch(JSONException ignored){}
        }
        throw new IllegalStateException("Сервер MCP не вернул JSON-RPC ответ. Нужен Streamable HTTP endpoint.");
    }
}
