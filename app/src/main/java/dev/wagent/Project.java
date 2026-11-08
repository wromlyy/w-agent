package dev.wagent;
import org.json.*;
import java.io.*;
import java.util.*;

/** SAF project changes have optimistic checks and encrypted per-file checkpoints. */
public final class Project {
    private final WApp app;private final FilesStore store;
    public Project(WApp app){this.app=app;store=new FilesStore(app);}
    private String read(String folder,String path)throws Exception{return store.execute(new JSONObject().put("action","read").put("folder",folder).put("path",path));}
    public JSONObject prepare(String folder,String path,String content,String diff)throws Exception{
        FilesStore.cleanPath(path);if(path.isEmpty())throw new IOException("Нужен путь файла");String before="";boolean exists=true;
        try{before=read(folder,path);}catch(FileNotFoundException e){exists=false;}
        String after=diff==null?content:TextPatch.apply(before,diff);if(after==null||after.length()>200000)throw new IOException("Файл: максимум 200 000 символов");
        return new JSONObject().put("folder",folder).put("path",path).put("before",before).put("after",after).put("existed",exists).put("preview",TextPatch.preview(before,after));
    }
    public JSONObject apply(JSONObject edit)throws Exception{
        String folder=edit.getString("folder"),path=edit.getString("path");String now="";boolean exists=true;try{now=read(folder,path);}catch(FileNotFoundException e){exists=false;}
        if(exists!=edit.getBoolean("existed")||!now.equals(edit.getString("before")))throw new IOException("Файл изменился после просмотра diff. Откройте его заново.");
        String id=UUID.randomUUID().toString();JSONObject checkpoint=new JSONObject(edit.toString()).put("id",id).put("time",System.currentTimeMillis()).put("state","prepared");
        synchronized(app.vault){JSONArray a=app.vault.get().optJSONArray("checkpoints");if(a==null)a=new JSONArray();a.put(checkpoint);while(a.length()>20)a.remove(0);app.vault.patch("checkpoints",a);}
        store.execute(new JSONObject().put("action","write").put("folder",folder).put("path",path).put("content",edit.getString("after")).put("overwrite",exists));
        String result=read(folder,path);if(!result.equals(edit.getString("after")))throw new IOException("Проверка записи не совпала. Checkpoint сохранён: "+id);
        return new JSONObject().put("checkpoint",id).put("verified",true).put("path",path).put("notice","Файл перечитан и совпадает. После правки кода запусти релевантные тесты или GitHub workflow.");
    }
    public JSONObject rollback(String id)throws Exception{
        JSONArray all=app.vault.get().optJSONArray("checkpoints");if(all!=null)for(int i=all.length()-1;i>=0;i--){JSONObject c=all.getJSONObject(i);if(!id.equals(c.optString("id")))continue;
            String current=read(c.getString("folder"),c.getString("path"));if(!current.equals(c.getString("after")))throw new IOException("Файл отличается от результата checkpoint. Откат остановлен, чтобы сохранить новые изменения.");
            store.execute(new JSONObject().put("action",c.getBoolean("existed")?"write":"delete").put("folder",c.getString("folder")).put("path",c.getString("path")).put("content",c.getString("before")).put("overwrite",true));
            synchronized(app.vault){JSONArray a=app.vault.get().getJSONArray("checkpoints");for(int n=0;n<a.length();n++)if(id.equals(a.getJSONObject(n).optString("id"))){a.remove(n);break;}app.vault.patch("checkpoints",a);}return new JSONObject().put("restored",true).put("path",c.getString("path"));
        }throw new IOException("Checkpoint не найден");
    }
    public String instructions(String folder)throws Exception{
        StringBuilder out=new StringBuilder();for(String name:new String[]{"AGENTS.md","W-AGENT.md"})try{String text=read(folder,name);out.append("\nИнструкции проекта ").append(name).append(":\n").append(text,0,Math.min(30000,text.length()));}catch(FileNotFoundException ignored){}
        return out.toString();
    }
    public String execute(JSONObject a)throws Exception{
        String op=a.getString("action"),folder=a.optString("folder",app.vault.get().optString("projectFolder"));if(folder.isEmpty())throw new IOException("Выберите рабочую папку в разделе Файлы");String path=FilesStore.cleanPath(a.optString("path"));
        if("instructions".equals(op))return instructions(folder);
        if("read_file".equals(op))return read(folder,path);
        if("list_dir".equals(op))return store.list(folder,path).toString();
        if("grep".equals(op)){String query=a.getString("query");if(query.isEmpty())throw new IOException("Нужен текст поиска");JSONArray hits=new JSONArray();grep(folder,path,query,hits,0,new int[]{0});return new JSONObject().put("matches",hits).put("notice","Поиск ограничен 200 файлами, глубиной 8 и 100 совпадениями").toString();}
        if("write_file".equals(op)||"edit_file".equals(op)){
            JSONObject change=prepare(folder,path,a.optString("content"),"edit_file".equals(op)?a.getString("diff"):null);DeviceService d=DeviceService.live;
            boolean automatic=LocalTrust.files(app.vault.get(),folder,app.workspace());if(automatic)app.agent.outputEvent(new FilesStore(app).output("change.diff",change.getString("preview")));boolean yes=automatic||(d!=null?d.approve("Применить diff: "+path,change.getString("preview")):MainActivity.approve("Применить diff: "+path,change.getString("preview")));if(!yes)return "Изменение отклонено. Не повторять.";return apply(change).toString();
        }
        throw new IOException("Неизвестный инструмент проекта");
    }
    private void grep(String folder,String path,String query,JSONArray hits,int depth,int[] count)throws Exception{
        if(depth>8||hits.length()>=100||count[0]>=200)return;JSONArray rows=store.list(folder,path);for(int i=0;i<rows.length()&&hits.length()<100&&count[0]<200;i++){JSONObject f=rows.getJSONObject(i);String p=f.getString("path"),name=f.getString("name");if(f.getBoolean("directory")){if(!Arrays.asList(".git","node_modules","build",".gradle").contains(name))grep(folder,p,query,hits,depth+1,count);continue;}count[0]++;try{String[] lines=read(folder,p).split("\n");for(int n=0;n<lines.length&&hits.length()<100;n++)if(lines[n].contains(query))hits.put(new JSONObject().put("path",p).put("line",n+1).put("text",lines[n].substring(0,Math.min(500,lines[n].length()))));}catch(IOException ignored){}}
    }
}
