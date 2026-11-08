package dev.wagent;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Net {
    public interface Cancel { boolean cancelled(); }
    public static final class Reply { public int status; public String text; public String session; public JSONObject json() throws Exception { return new JSONObject(text); } }
    public static Reply request(String method, String url, Map<String,String> headers, String body, Cancel cancel) throws Exception {
        URI uri=URI.create(url);
        if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw new IOException("Укажите HTTPS-адрес без логина и пароля");
        HttpURLConnection con=(HttpURLConnection)uri.toURL().openConnection();
        con.setRequestMethod(method); con.setConnectTimeout(15000); con.setReadTimeout(60000); con.setInstanceFollowRedirects(false);
        con.setRequestProperty("Accept","application/json");
        if(headers!=null) for(Map.Entry<String,String> h:headers.entrySet()) con.setRequestProperty(h.getKey(),h.getValue());
        Thread watcher=new Thread(()->{try{while(!Thread.currentThread().isInterrupted()){if(cancel!=null&&cancel.cancelled()){con.disconnect();return;}Thread.sleep(150);}}catch(InterruptedException ignored){}},"network-cancel");
        watcher.setDaemon(true); watcher.start();
        try {
            if(cancel!=null&&cancel.cancelled())throw new InterruptedException("Остановлено");
            if(body!=null){con.setDoOutput(true);con.setRequestProperty("Content-Type","application/json");try(OutputStream out=con.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
            Reply r=new Reply(); r.status=con.getResponseCode(); r.session=con.getHeaderField("Mcp-Session-Id");
            InputStream stream=r.status>=400?con.getErrorStream():con.getInputStream();
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            boolean sse=String.valueOf(con.getContentType()).startsWith("text/event-stream");
            if(stream!=null&&sse)try(BufferedReader reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))){
                String line;StringBuilder event=new StringBuilder();int size=0;
                while((line=reader.readLine())!=null){size+=line.length();if(size>4*1024*1024)throw new IOException("Ответ сервера больше 4 МБ");
                    if(line.isEmpty()){
                        try{JSONObject payload=new JSONObject(event.toString());if(payload.has("result")||payload.has("error")){bytes.write(payload.toString().getBytes(StandardCharsets.UTF_8));break;}}catch(JSONException ignored){}event.setLength(0);
                    }else if(line.startsWith("data:"))event.append(line.substring(5).trim());
                }
            }
            else if(stream!=null)try(InputStream in=stream){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(bytes.size()+n>4*1024*1024)throw new IOException("Ответ сервера больше 4 МБ");bytes.write(b,0,n);}}
            r.text=bytes.toString(StandardCharsets.UTF_8.name());
            if(r.status<200||r.status>=300) {
                String detail="";try {JSONObject e=new JSONObject(r.text).optJSONObject("error");if(e!=null)detail=e.optString("message","");}catch(Exception ignored){}
                if(detail.length()>300)detail=detail.substring(0,300);
                throw new IOException("HTTP "+r.status+(detail.isEmpty()?"":": "+detail));
            }
            return r;
        } finally {watcher.interrupt();con.disconnect();}
    }
    public static Map<String,String> bearer(String token) { Map<String,String> h=new HashMap<>();if(!token.trim().isEmpty())h.put("Authorization","Bearer "+token);return h; }
    public static String join(String base,String path) {return base.replaceAll("/+$","")+"/"+path.replaceAll("^/+","");}
}
