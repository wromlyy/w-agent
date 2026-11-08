package dev.wagent;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

/** Workflow operations and authenticated artifact delivery. Tokens never follow redirects. */
public final class GitHubBuilds {
    private final WApp app;
    public GitHubBuilds(WApp app){this.app=app;}
    public static String repo(String raw){if(!raw.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")||raw.contains(".."))throw new IllegalArgumentException("Репозиторий: owner/repo");return raw;}
    private static String segment(String value)throws java.io.UnsupportedEncodingException{return java.net.URLEncoder.encode(value,"UTF-8").replace("+","%20");}
    public static JSONObject connection(JSONObject settings,String id)throws Exception{
        JSONArray a=settings.getJSONArray("services");for(int i=0;i<a.length();i++){JSONObject s=a.getJSONObject(i);if(id.equals(s.optString("id"))&&s.optBoolean("enabled",true)&&"https://api.github.com".equals(s.optString("url").replaceAll("/+$","")))return s;}throw new IllegalArgumentException("Подключите GitHub REST API в Связи → Сервисы");
    }
    private String api(JSONObject s,String method,String path,JSONObject body,Net.Cancel cancel)throws Exception{return Connections.execute(s,new JSONObject().put("method",method).put("path",path).put("body",body==null?"{}":body.toString()),cancel);}
    public String execute(JSONObject settings,JSONObject a,Net.Cancel cancel)throws Exception{
        JSONObject service=connection(settings,a.getString("connection"));String root="repos/"+repo(a.getString("repo"))+"/actions/",action=a.getString("action");
        switch(action){
            case "workflows":return api(service,"GET",root+"workflows?per_page=50",null,cancel);
            case "dispatch":{
                JSONObject body=new JSONObject().put("ref",a.optString("ref","main"));String raw=a.optString("inputs_json");if(!raw.isEmpty())body.put("inputs",new JSONObject(raw));
                api(service,"POST",root+"workflows/"+segment(a.getString("workflow"))+"/dispatches",body,cancel);
                return "workflow_dispatch принят GitHub. Это НЕ означает успешную сборку. Найди новый run нужного workflow/ref через runs, проверь head_sha, event и created_at; дождись status=completed и conclusion=success, затем получи artifacts. Не выбирай старую сборку.";
            }
            case "runs":return api(service,"GET",root+"workflows/"+segment(a.getString("workflow"))+"/runs?per_page=20&branch="+segment(a.optString("ref","main")),null,cancel);
            case "run":return api(service,"GET",root+"runs/"+positive(a.getLong("run_id")),null,cancel);
            case "jobs":return api(service,"GET",root+"runs/"+positive(a.getLong("run_id"))+"/jobs?per_page=50",null,cancel);
            case "artifacts":return api(service,"GET",root+"runs/"+positive(a.getLong("run_id"))+"/artifacts?per_page=50",null,cancel);
            case "cancel":return api(service,"POST",root+"runs/"+positive(a.getLong("run_id"))+"/cancel",new JSONObject(),cancel);
            case "download":{
                long artifact=positive(a.getLong("artifact_id"));JSONObject meta=new JSONObject(api(service,"GET",root+"artifacts/"+artifact,null,cancel));if(meta.optBoolean("expired"))throw new IOException("Артефакт истёк; пересоберите проект");
                File zip=File.createTempFile("artifact-",".zip",app.getCacheDir());
                try{download("https://api.github.com/"+root+"artifacts/"+artifact+"/zip",service.optString("token"),zip,cancel);
                    try(ZipFile archive=new ZipFile(zip)){List<ZipEntry> candidates=new ArrayList<>();Enumeration<? extends ZipEntry> entries=archive.entries();while(entries.hasMoreElements()){ZipEntry e=entries.nextElement();if(!e.isDirectory()&&e.getName().toLowerCase(Locale.ROOT).endsWith(".apk"))candidates.add(e);}
                        String entry=a.optString("entry");ZipEntry selected=null;if(!entry.isEmpty())for(ZipEntry e:candidates)if(entry.equals(e.getName()))selected=e;
                        if(selected==null&&entry.isEmpty()&&candidates.size()==1)selected=candidates.get(0);
                        if(selected==null){JSONArray names=new JSONArray();for(ZipEntry e:candidates)names.put(e.getName());return new JSONObject().put("apk_entries",names).put("notice","Укажите entry с точным путём APK из списка. Если список пуст, workflow не загрузил APK в этот артефакт.").toString();}
                        String name=selected.getName().substring(selected.getName().lastIndexOf('/')+1);try(InputStream in=archive.getInputStream(selected)){JSONObject file=new FilesStore(app).importOutput(name,"application/vnd.android.package-archive",in,200L*1024*1024,cancel);app.agent.outputEvent(file);return file.toString();}
                    }
                }finally{zip.delete();}
            }
            default:throw new IllegalArgumentException("Неизвестная операция GitHub Actions");
        }
    }
    private static long positive(long n){if(n<=0)throw new IllegalArgumentException("Нужен положительный ID");return n;}
    private void download(String url,String token,File destination,Net.Cancel cancel)throws Exception{
        for(int redirects=0;redirects<5;redirects++){
            URI uri=URI.create(url);String host=uri.getHost();if(!"https".equals(uri.getScheme())||host==null||uri.getUserInfo()!=null||!("api.github.com".equals(host)||host.endsWith(".githubusercontent.com")||host.endsWith(".blob.core.windows.net")||host.endsWith(".actions.githubusercontent.com")))throw new IOException("Неожиданный адрес артефакта");
            HttpURLConnection c=(HttpURLConnection)uri.toURL().openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(30000);c.setRequestProperty("Accept","application/vnd.github+json");if("api.github.com".equals(host))c.setRequestProperty("Authorization","Bearer "+token);
            try{int status=c.getResponseCode();if(status>=300&&status<400){url=uri.resolve(c.getHeaderField("Location")).toString();continue;}if(status!=200)throw new IOException("Загрузка артефакта: HTTP "+status);
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(destination)){byte[] b=new byte[32768];int n;long total=0;while((n=in.read(b))!=-1){if(cancel.cancelled())throw new InterruptedException("Загрузка остановлена");total+=n;if(total>200L*1024*1024)throw new IOException("Артефакт больше 200 МБ");out.write(b,0,n);}}return;
            }finally{c.disconnect();}
        }throw new IOException("Слишком много перенаправлений артефакта");
    }
}
