package dev.wagent;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Files stay within user-granted SAF trees or W Agent's generated-file directory. */
public final class FilesStore {
    private final WApp app;
    private ContentResolver resolver(){return app.getContentResolver();}
    public FilesStore(WApp app){this.app=app;}
    public synchronized JSONObject addFolder(Uri tree)throws Exception{
        Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        JSONObject row=info(root);JSONObject state=app.vault.get();JSONArray folders=state.optJSONArray("folders");if(folders==null)folders=new JSONArray();
        for(int i=0;i<folders.length();i++)if(tree.toString().equals(folders.getJSONObject(i).optString("uri")))return folders.getJSONObject(i);
        JSONObject folder=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",row.optString("name","Папка")).put("uri",tree.toString());folders.put(folder);app.vault.save(state.put("folders",folders));return folder;
    }
    public synchronized void removeFolder(String id)throws Exception{
        JSONObject state=app.vault.get();JSONArray folders=state.optJSONArray("folders");if(folders==null)return;
        for(int i=0;i<folders.length();i++)if(id.equals(folders.getJSONObject(i).getString("id"))){Uri uri=Uri.parse(folders.getJSONObject(i).getString("uri"));try{resolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}catch(Exception ignored){}folders.remove(i);break;}
        app.vault.save(state.put("folders",folders));
    }
    public JSONArray folders()throws Exception {
        JSONArray raw=app.vault.get().optJSONArray("folders"),out=new JSONArray();if(raw!=null)for(int i=0;i<raw.length();i++){JSONObject f=raw.getJSONObject(i);out.put(new JSONObject().put("id",f.getString("id")).put("name",f.optString("name")));}return out;
    }
    private Uri tree(String id)throws Exception{JSONArray folders=app.vault.get().optJSONArray("folders");if(folders!=null)for(int i=0;i<folders.length();i++)if(id.equals(folders.getJSONObject(i).getString("id")))return Uri.parse(folders.getJSONObject(i).getString("uri"));throw new IOException("Подключите папку в разделе Файлы");}
    public static String cleanPath(String raw)throws IOException{
        if(raw==null||raw.isEmpty())return "";
        if(raw.startsWith("/")||raw.contains("\\")||raw.indexOf('\0')>=0)throw new IOException("Нужен относительный путь внутри выбранной папки");
        String[] parts=raw.split("/",-1);for(String p:parts)if(p.isEmpty()||p.equals(".")||p.equals(".."))throw new IOException("Недопустимый путь");
        return raw;
    }
    private Uri resolve(String folder,String path)throws Exception{
        path=cleanPath(path);Uri tree=tree(folder),current=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        if(path.isEmpty())return current;
        for(String segment:path.split("/")){Uri child=child(current,segment);if(child==null)throw new FileNotFoundException("Не найдено: "+path);current=child;}
        return current;
    }
    private Uri child(Uri parent,String name)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
        try(Cursor c=resolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(c!=null)while(c.moveToNext())if(name.equals(c.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(parent,c.getString(0));
        }return null;
    }
    private JSONObject info(Uri document)throws Exception {
        String[] columns={DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=resolver().query(document,columns,null,null,null)){if(c!=null&&c.moveToFirst())return new JSONObject().put("name",c.getString(0)).put("mime",c.getString(1)).put("directory",DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1))).put("size",c.isNull(2)?0:c.getLong(2)).put("modified",c.isNull(3)?0:c.getLong(3));}
        throw new IOException("Не удалось прочитать файл. Проверьте доступ к папке.");
    }
    public JSONArray list(String folder,String path)throws Exception{
        Uri parent=resolve(folder,path);if(!info(parent).optBoolean("directory"))throw new IOException("Это файл, а не папка");
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));List<JSONObject> rows=new ArrayList<>();
        try(Cursor c=resolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){if(c!=null)while(c.moveToNext()&&rows.size()<500){JSONObject row=info(DocumentsContract.buildDocumentUriUsingTree(parent,c.getString(0)));row.put("path",path.isEmpty()?row.getString("name"):path+"/"+row.getString("name"));rows.add(row);}}
        rows.sort((a,b)->{int dir=Boolean.compare(b.optBoolean("directory"),a.optBoolean("directory"));return dir!=0?dir:a.optString("name").compareToIgnoreCase(b.optString("name"));});return new JSONArray(rows);
    }
    private byte[] read(Uri uri,int limit)throws Exception{
        try(InputStream in=resolver().openInputStream(uri)){if(in==null)throw new IOException("Не удалось открыть файл");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){if(out.size()+n>limit)throw new IOException("Размер файла превышает "+limit/1024+" КБ");out.write(buf,0,n);}return out.toByteArray();}
    }
    public JSONObject attachment(String folder,String path)throws Exception{
        Uri uri=resolve(folder,path);JSONObject meta=info(uri);byte[] bytes=read(uri,4*1024*1024);String mime=meta.getString("mime"),name=meta.getString("name");
        JSONObject file=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("mime",mime).put("size",bytes.length);
        if(isText(mime,name)){if(bytes.length>200000)throw new IOException("Текстовое вложение: максимум 200 КБ");file.put("text",new String(bytes,StandardCharsets.UTF_8));}
        else if(mime.startsWith("image/")||mime.equals("application/pdf"))file.put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
        else throw new IOException("Для чата поддерживаются текст, изображения и PDF");return file;
    }
    private static boolean isText(String mime,String name){return mime.startsWith("text/")||mime.equals("application/json")||name.matches("(?i).+\\.(md|txt|json|csv|js|ts|java|py|xml|html|css|yaml|yml|log|sh)$");}
    public static String mime(String name){String n=name.toLowerCase(Locale.ROOT);if(n.endsWith(".md"))return "text/markdown";if(n.endsWith(".csv"))return "text/csv";if(n.endsWith(".json"))return "application/json";if(n.endsWith(".html"))return "text/html";return "text/plain";}
    private Uri writeTarget(JSONObject a,boolean directory)throws Exception{
        String path=cleanPath(a.getString("path"));if(path.isEmpty())throw new IOException("Укажите имя файла или папки");int slash=path.lastIndexOf('/');String name=slash<0?path:path.substring(slash+1),parentPath=slash<0?"":path.substring(0,slash);
        Uri parent=resolve(a.getString("folder"),parentPath),existing=child(parent,name);
        if(existing!=null){if(directory)throw new IOException("Папка с таким именем уже существует");if(!a.optBoolean("overwrite"))throw new IOException("Файл существует. Для замены требуется overwrite=true и подтверждение");if(info(existing).optBoolean("directory"))throw new IOException("Нельзя перезаписать папку");return existing;}
        Uri created=DocumentsContract.createDocument(resolver(),parent,directory?DocumentsContract.Document.MIME_TYPE_DIR:mime(name),name);if(created==null)throw new IOException("Провайдер не создал файл. Проверьте право записи.");return created;
    }
    public String execute(JSONObject a)throws Exception{
        String action=a.getString("action");if("folders".equals(action))return folders().toString();
        String folder=a.optString("folder"),path=cleanPath(a.optString("path"));
        if("list".equals(action))return list(folder,path).toString();
        if("read".equals(action)){Uri uri=resolve(folder,path);JSONObject meta=info(uri);if(!isText(meta.getString("mime"),meta.getString("name")))throw new IOException("Этот файл не текстовый. Используйте attach, чтобы выдать его пользователю.");return new String(read(uri,200000),StandardCharsets.UTF_8);}
        if("write".equals(action)){byte[] bytes=a.getString("content").getBytes(StandardCharsets.UTF_8);if(bytes.length>1024*1024)throw new IOException("Максимум 1 МБ для создаваемого текста");Uri target=writeTarget(a,false);try(OutputStream out=resolver().openOutputStream(target,"wt")){if(out==null)throw new IOException("Нет доступа для записи");out.write(bytes);}return info(target).toString();}
        if("mkdir".equals(action))return info(writeTarget(a,true)).toString();
        if(path.isEmpty())throw new IOException("Операции с корнем подключённой папки не разрешены");
        Uri source=resolve(folder,path);
        if("delete".equals(action)){if(!DocumentsContract.deleteDocument(resolver(),source))throw new IOException("Провайдер не удалил файл");return "Удалено";}
        if("rename".equals(action)){String name=cleanPath(a.getString("name"));if(name.contains("/")||name.isEmpty())throw new IOException("Нужно новое имя без пути");Uri renamed=DocumentsContract.renameDocument(resolver(),source,name);if(renamed==null)throw new IOException("Не удалось переименовать");return info(renamed).toString();}
        if("copy".equals(action)||"move".equals(action)){
            JSONObject meta=info(source);if(meta.optBoolean("directory"))throw new IOException("Копирование и перемещение поддерживаются для файлов");
            Uri destination=resolve(a.optString("toFolder",folder),cleanPath(a.getString("toPath")));if(!info(destination).optBoolean("directory"))throw new IOException("toPath должен указывать папку");
            if(child(destination,meta.getString("name"))!=null)throw new IOException("В папке назначения уже есть такой файл");
            // Stream between document providers; Android checks both persisted grants.
            Uri created=DocumentsContract.createDocument(resolver(),destination,meta.getString("mime"),meta.getString("name"));if(created==null)throw new IOException("Не удалось создать копию");
            try(InputStream in=resolver().openInputStream(source);OutputStream out=resolver().openOutputStream(created,"wt")){if(in==null||out==null)throw new IOException("Не удалось открыть потоки файлов");byte[] b=new byte[32768];int n;long total=0;while((n=in.read(b))!=-1){total+=n;if(total>100L*1024*1024)throw new IOException("Копирование: максимум 100 МБ");out.write(b,0,n);}}
            catch(Exception e){try{DocumentsContract.deleteDocument(resolver(),created);}catch(Exception ignored){}throw e;}
            if("move".equals(action)&&!DocumentsContract.deleteDocument(resolver(),source))return "Копия создана, но исходный файл не удалён";
            return info(created).toString();
        }
        throw new IOException("Неизвестная операция с файлами");
    }
    public JSONObject output(String name,String text)throws Exception{return outputBytes(name,mime(name),text.getBytes(StandardCharsets.UTF_8));}
    public JSONObject attach(String folder,String path)throws Exception{Uri uri=resolve(folder,path);JSONObject m=info(uri);return outputBytes(m.getString("name"),m.getString("mime"),read(uri,4*1024*1024));}
    private synchronized JSONObject outputBytes(String name,String mime,byte[] bytes)throws Exception{
        name=cleanPath(name);if(name.isEmpty()||name.contains("/"))throw new IOException("Укажите имя без пути");if(bytes.length>4*1024*1024)throw new IOException("Выдаваемый файл: максимум 4 МБ");
        String id=UUID.randomUUID().toString();File directory=new File(app.getFilesDir(),"output");if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось создать хранилище файлов");
        File target=new File(directory,id);try(FileOutputStream out=new FileOutputStream(target)){out.write(bytes);}
        JSONObject file=new JSONObject().put("id",id).put("name",name).put("mime",mime).put("size",bytes.length).put("created",System.currentTimeMillis());
        try{JSONObject state=app.vault.get();JSONArray outputs=state.optJSONArray("outputs");if(outputs==null)outputs=new JSONArray();outputs.put(file);app.vault.save(state.put("outputs",outputs));}
        catch(Exception e){target.delete();throw e;}return file;
    }
    public JSONObject outputInfo(String id)throws Exception{JSONArray outputs=app.vault.get().optJSONArray("outputs");if(outputs!=null)for(int i=0;i<outputs.length();i++)if(id.equals(outputs.getJSONObject(i).getString("id")))return outputs.getJSONObject(i);throw new FileNotFoundException("Файл не найден");}
    public File outputFile(String id)throws Exception{outputInfo(id);if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Недопустимый идентификатор");return new File(new File(app.getFilesDir(),"output"),id);}
}
