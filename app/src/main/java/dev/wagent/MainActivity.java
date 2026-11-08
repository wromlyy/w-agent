package dev.wagent;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.view.*;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static volatile java.lang.ref.WeakReference<MainActivity> foreground=new java.lang.ref.WeakReference<>(null);
    private static volatile CompletableFuture<Boolean> approval;
    private WebView web;
    private Voice voice;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String pickerId,pickerMode;
    private JSONObject exportData;
    private String saveOutputId;
    private WApp app(){return (WApp)getApplication();}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);voice=new Voice(this);
        web=new WebView(this);web.setBackgroundColor(Color.rgb(245,244,240));setContentView(web);
        applyTheme();
        if(Build.VERSION.SDK_INT>=35){getWindow().setDecorFitsSystemWindows(false);web.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});}
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(false);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri u=request.getUrl();if("https".equals(u.getScheme())&&"app.wagent.local".equals(u.getHost())){
                    String path=u.getPath();if(path==null||path.contains(".."))return empty();
                    if("/".equals(path))path="/index.html";
                    try{String type=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".svg")?"image/svg+xml":"text/html";
                        return new WebResourceResponse(type,"UTF-8",200,"OK",Collections.singletonMap("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; base-uri 'none'; form-action 'none'; frame-src 'none'; connect-src 'none'"),getAssets().open(path.substring(1)));
                    }catch(IOException e){return empty();}
                }return empty();
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            private WebResourceResponse empty(){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
        });
        web.addJavascriptInterface(new Bridge(),"WNative");web.loadUrl("https://app.wagent.local/index.html");
    }
    @Override protected void onResume(){super.onResume();foreground=new java.lang.ref.WeakReference<>(this);if(web!=null)web.onResume();}
    private void applyTheme(){runOnUiThread(()->{
        boolean dark="dark".equals(app().vault.get().optString("theme"));int background=dark?Color.rgb(23,27,24):Color.rgb(245,244,240);
        getWindow().setStatusBarColor(background);getWindow().setNavigationBarColor(background);if(web!=null)web.setBackgroundColor(background);
        getWindow().getDecorView().setBackgroundColor(background);
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController c=getWindow().getInsetsController();if(c!=null)c.setSystemBarsAppearance(dark?0:WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}else{int flags=getWindow().getDecorView().getSystemUiVisibility();int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;getWindow().getDecorView().setSystemUiVisibility(dark?flags&~mask:flags|mask);}
    });}
    @Override protected void onPause(){foreground.clear();if(web!=null)web.onPause();super.onPause();}
    public static boolean approve(String title,String detail) throws Exception {
        MainActivity a=foreground.get();if(a==null)throw new IllegalStateException("Откройте W Agent для подтверждения действия сервиса");
        CompletableFuture<Boolean> answer=new CompletableFuture<>();approval=answer;
        a.runOnUiThread(()->{AlertDialog dialog=new AlertDialog.Builder(a).setTitle(title).setMessage(detail).setNegativeButton("Отклонить",(d,w)->answer.complete(false)).setPositiveButton("Разрешить",(d,w)->answer.complete(true)).setOnCancelListener(d->answer.complete(false)).create();dialog.show();answer.whenComplete((value,error)->a.runOnUiThread(dialog::dismiss));});
        try{return answer.get(120,TimeUnit.SECONDS);}catch(TimeoutException e){answer.complete(false);return false;}finally{approval=null;}
    }
    public static void cancelApproval(){CompletableFuture<Boolean> p=approval;if(p!=null)p.complete(false);}
    @Override protected void onDestroy(){io.shutdownNow();if(voice!=null)voice.close();if(web!=null){web.removeJavascriptInterface("WNative");web.destroy();web=null;}super.onDestroy();}
    @Override public void onBackPressed(){if(web!=null)web.evaluateJavascript("window.goBack && window.goBack()",null);}
    private void reply(String id,Object value,String error){
        String encoded;
        try{String wrapped=new JSONArray().put(value).toString();encoded=wrapped.substring(1,wrapped.length()-1);}catch(Exception e){encoded="null";}
        String script="window.nativeReply("+JSONObject.quote(id)+","+encoded+","+(error==null?"null":JSONObject.quote(error))+")";
        handler.post(()->{if(web!=null)web.evaluateJavascript(script,null);});
    }
    private final class Bridge {
        @JavascriptInterface public void post(String raw){
            io.execute(()->{String id="";try{JSONObject req=new JSONObject(raw);id=req.getString("id");String op=req.getString("op");JSONObject args=req.optJSONObject("args");if(args==null)args=new JSONObject();Object result=null;
                switch(op){
                    case "init":{JSONObject visible=app().vault.get();visible.remove("pendingAuth");if(!visible.has("roles"))visible.put("roles",Roles.presets());result=new JSONObject().put("settings",visible).put("accessibility",DeviceService.live!=null).put("device",DeviceStatus.get(MainActivity.this)).put("run",app().agent.state()).put("version","0.2.0");break;}
                    case "state":result=new JSONObject().put("run",app().agent.state()).put("device",DeviceStatus.get(MainActivity.this)).put("accessibility",DeviceService.live!=null);break;
                    case "reconnect":handler.post(()->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));result=true;break;
                    case "deviceTest":{
                        DeviceService service=DeviceService.live;if(service==null)throw new IllegalStateException(DeviceStatus.enabled(MainActivity.this)?"Служба включена, но Android её не подключил. Нажмите Восстановить, затем выключите и включите W Agent в специальных возможностях, если подключение не появится.":"Сначала включите W Agent в специальных возможностях");
                        JSONObject screen=new JSONObject(service.screen());result=new JSONObject().put("connected",true).put("elements",screen.getJSONArray("elements").length()).put("screenAvailable",screen.has("package"));break;
                    }
                    case "save":app().vault.updateSettings(args);applyTheme();result=true;break;
                    case "voiceListen":{String callback=id;handler.post(()->voice.listen((text,error)->reply(callback,text,error)));return;}
                    case "voiceSpeak":{String callback=id,text=args.getString("text");handler.post(()->voice.speak(text,(value,error)->reply(callback,value,error)));return;}
                    case "voiceStop":handler.post(voice::stop);result=true;break;
                    case "compactContext":result=app().agent.compact();break;
                    case "projectRead":result=new Project(app()).execute(args.put("action","read_file"));break;
                    case "projectWrite":{
                        Project project=new Project(app());JSONObject change=project.prepare(args.getString("folder"),args.getString("path"),args.getString("content"),null);
                        if(!approve("Применить diff?",change.getString("preview")))throw new IllegalStateException("Изменение отклонено");result=project.apply(change);break;
                    }
                    case "checkpointRollback":if(!approve("Откатить файл?","Восстановить содержимое до выбранной правки?"))throw new IllegalStateException("Откат отклонён");result=new Project(app()).rollback(args.getString("id"));break;
                    case "memoryDelete":{synchronized(app().vault){JSONArray a=app().vault.get().optJSONArray("memories");if(a!=null)for(int n=0;n<a.length();n++)if(args.getString("id").equals(a.getJSONObject(n).optString("id"))){a.remove(n);break;}app().vault.patch("memories",a==null?new JSONArray():a);}result=true;break;}
                    case "terminalList":result=app().shell.list();break;
                    case "terminalKill":app().shell.kill(args.getString("id"));result=true;break;
                    case "terminalRun":{
                        String cmd=args.getString("cmd");if(ShellPolicy.blocked(cmd))throw new IllegalArgumentException("Команда запрещена политикой терминала");
                        if(!ShellPolicy.autoRead(cmd)&&!approve("Выполнить команду?",cmd+"\nРабочая папка: "+args.optString("cwd","workspace")))throw new IllegalStateException("Команда отклонена");
                        result=app().shell.start(cmd,args.optString("cwd"),args.optInt("timeout",60));break;
                    }
                    case "run":app().agent.start(args.getString("task"),args.optJSONArray("files"));result=true;break;
                    case "planApprove":app().agent.approvePlan(args.getString("plan"));result=true;break;
                    case "pause":app().agent.pause(args.optBoolean("paused",true));result=true;break;
                    case "stop":app().agent.stop();result=true;break;
                    case "newChat":app().agent.newChat();result=true;break;
                    case "chatSelect":app().agent.selectChat(args.getString("chatId"));result=true;break;
                    case "copyText":{String value=args.getString("text");handler.post(()->getSystemService(android.content.ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("W Agent",value)));result=true;break;}
                    case "folders":result=new FilesStore(app()).folders();break;
                    case "folderRemove":new FilesStore(app()).removeFolder(args.getString("id"));result=true;break;
                    case "fileList":result=new FilesStore(app()).list(args.getString("folder"),args.optString("path"));break;
                    case "fileAttach":result=new FilesStore(app()).attachment(args.getString("folder"),args.getString("path"));break;
                    case "fileDeliver":result=new FilesStore(app()).attach(args.getString("folder"),args.getString("path"));break;
                    case "fileOutput":result=new FilesStore(app()).output(args.getString("name"),args.getString("content"));break;
                    case "fileShare":{
                        String fileId=args.getString("id");JSONObject file=new FilesStore(app()).outputInfo(fileId);Uri uri=Uri.parse("content://dev.wagent.files/output/"+fileId);
                        Intent share=new Intent(Intent.ACTION_SEND).setType(file.getString("mime")).putExtra(Intent.EXTRA_STREAM,uri).putExtra(Intent.EXTRA_TITLE,file.getString("name")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri(file.getString("name"),uri));handler.post(()->startActivity(Intent.createChooser(share,"Поделиться файлом")));result=true;break;
                    }
                    case "fileSave":{
                        String outputId=args.getString("id");JSONObject file=new FilesStore(app()).outputInfo(outputId);String callback=id;
                        handler.post(()->{if(pickerId!=null){reply(callback,null,"Выбор файла уже открыт");return;}pickerId=callback;pickerMode="saveOutput";saveOutputId=outputId;startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(file.optString("mime","text/plain")).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,file.optString("name","file.txt")),51);});return;
                    }
                    case "folderPick":{String callback=id;handler.post(()->{if(pickerId!=null){reply(callback,null,"Выбор файла уже открыт");return;}pickerId=callback;pickerMode="folder";startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION),51);});return;}
                    case "models":result=Models.models(args,()->false);break;
                    case "freeModels":result=Models.freeModels(()->false);break;
                    case "authBegin":{
                        if(app().agent.running())throw new IllegalStateException("Остановите задачу перед входом в аккаунт");
                        JSONObject flow=OpenRouterAuth.begin(System.currentTimeMillis());app().vault.save(app().vault.get().put("pendingAuth",flow));
                        String url=OpenRouterAuth.url(flow);handler.post(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))));result=true;break;
                    }
                    case "authComplete":{
                        JSONObject state=app().vault.get();String key=OpenRouterAuth.exchange(state.optJSONObject("pendingAuth"),args.getString("code"),System.currentTimeMillis(),()->false);
                        state=app().vault.get();state.remove("pendingAuth");
                        JSONArray profiles=state.getJSONArray("profiles");String profileId="openrouter-login";
                        JSONObject profile=new JSONObject().put("id",profileId).put("preset","openrouter").put("name","OpenRouter · бесплатно").put("type","compatible").put("base","https://openrouter.ai/api/v1").put("key",key).put("model","openrouter/free").put("freeOnly",true).put("mode","agent");
                        boolean found=false;for(int i=0;i<profiles.length();i++)if(profileId.equals(profiles.getJSONObject(i).optString("id"))){profiles.put(i,profile);found=true;break;}
                        if(!found)profiles.put(profile);app().vault.save(state.put("profiles",profiles).put("activeProfile",profileId));result=true;break;
                    }
                    case "authCancel":{JSONObject state=app().vault.get();state.remove("pendingAuth");app().vault.save(state);result=true;break;}
                    case "openAccount":{
                        if(app().agent.running())throw new IllegalStateException("Остановите задачу перед входом в аккаунт");
                        Map<String,String> accounts=new HashMap<>();accounts.put("chatgpt","https://chatgpt.com/");accounts.put("gemini","https://gemini.google.com/");accounts.put("grok","https://grok.com/");accounts.put("claude","https://claude.ai/");accounts.put("kimi","https://www.kimi.com/");accounts.put("deepseek","https://chat.deepseek.com/");accounts.put("qwen","https://chat.qwen.ai/");accounts.put("relay","https://codex-relay.codes/");
                        String url=accounts.get(args.optString("account"));if(url==null)throw new IllegalArgumentException("Неизвестный сервис");
                        handler.post(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url))));result=true;break;
                    }
                    case "testService":result=Connections.execute(args,new JSONObject().put("method","GET").put("path","api.github.com".equals(Uri.parse(args.optString("url")).getHost())?"user":"").put("operation","tools/list"),()->false);break;
                    case "apps":result=apps(MainActivity.this,true);break;
                    case "appSettings":handler.post(()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));result=true;break;
                    case "accessibility":handler.post(()->{new AlertDialog.Builder(MainActivity.this).setTitle("Управление телефоном").setMessage("Во время задачи W Agent сможет читать открытый экран, нажимать кнопки и вводить текст. Текст экрана и, если вы включите снимки, изображения отправляются выбранному поставщику модели. Включите W Agent в разделе скачанных служб. Остановить задачу можно кнопкой W поверх приложений.").setNegativeButton("Отмена",null).setPositiveButton("Открыть настройки",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();});result=true;break;
                    case "notifications":handler.post(()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);});result=true;break;
                    case "pick":{String callback=id,mode=args.optString("mode","files");handler.post(()->pick(callback,mode));return;}
                    case "export":{exportData=app().vault.get();String callback=id;handler.post(()->{pickerId=callback;pickerMode="export";Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"w-agent-history.json");startActivityForResult(i,51);});return;}
                    case "clearHistory":{if(app().agent.running())throw new IllegalStateException("Сначала остановите задачу");app().agent.newChat();JSONObject next=app().vault.get().put("history",new JSONArray());app().vault.save(next);result=true;break;}
                    case "openLink":{Uri uri=Uri.parse(args.getString("url"));if(!"https".equals(uri.getScheme()))throw new IllegalArgumentException("Нужна HTTPS-ссылка");handler.post(()->startActivity(new Intent(Intent.ACTION_VIEW,uri)));result=true;break;}
                    default:throw new IllegalArgumentException("Неизвестная команда");
                }reply(id,result,null);
            }catch(Exception e){reply(id,null,e.getMessage());}});
        }
    }
    private void pick(String id,String mode){
        if(pickerId!=null){reply(id,null,"Выбор файла уже открыт");return;}
        pickerId=id;pickerMode=mode;
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,!"skill".equals(mode));
        if(!"skill".equals(mode))i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"text/*","application/json","application/pdf","image/*","application/octet-stream"});
        try{startActivityForResult(i,51);}catch(Exception e){pickerId=null;reply(id,null,"Не удалось открыть выбор файлов");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(request!=51)return;
        String id=pickerId,mode=pickerMode;pickerId=null;pickerMode=null;
        if(result!=RESULT_OK||data==null){reply(id,new JSONArray(),null);return;}
        io.execute(()->{try{
            if("folder".equals(mode)){
                Uri tree=data.getData();if(tree==null)throw new IOException("Папка не выбрана");if((data.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)==0)throw new IOException("Android не выдал доступ к папке");if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);else getContentResolver().takePersistableUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION);reply(id,new FilesStore(app()).addFolder(tree),null);return;
            }
            if("saveOutput".equals(mode)){
                FilesStore store=new FilesStore(app());try(InputStream in=new FileInputStream(store.outputFile(saveOutputId));OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Файл недоступен для записи");byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}saveOutputId=null;reply(id,true,null);return;
            }
            if("export".equals(mode)){
                try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Файл недоступен");out.write(new JSONObject().put("app","W Agent").put("history",exportData.getJSONArray("history")).toString(2).getBytes(StandardCharsets.UTF_8));}exportData=null;reply(id,true,null);return;
            }
            List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<Math.min(6,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());
            JSONArray files=new JSONArray();int total=0;
            for(Uri uri:uris){String name="file";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
                byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Не удалось прочитать файл");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(out.size()+n>20*1024*1024)throw new IOException("Исходный файл: максимум 20 МБ; фото уменьшаются автоматически");out.write(chunk,0,n);}bytes=out.toByteArray();}
                String mime=getContentResolver().getType(uri);JSONObject file=Attachments.create(name,mime,bytes,"skill".equals(mode));total+=file.getInt("size");if(total>10*1024*1024)throw new IOException("Общий размер подготовленных вложений больше 10 МБ");files.put(file);
            }reply(id,files,null);
        }catch(Exception e){reply(id,null,e.getMessage());}});
    }
    public static JSONArray apps(Context c,boolean icons) throws Exception {
        Intent i=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> found=c.getPackageManager().queryIntentActivities(i,0);
        found.sort(Comparator.comparing(a->a.loadLabel(c.getPackageManager()).toString(),String.CASE_INSENSITIVE_ORDER));
        JSONArray out=new JSONArray();Set<String> seen=new HashSet<>();
        for(ResolveInfo r:found){String pkg=r.activityInfo.packageName;if(!seen.add(pkg)||pkg.equals(c.getPackageName()))continue;
            JSONObject row=new JSONObject().put("name",r.loadLabel(c.getPackageManager()).toString()).put("package",pkg);
            if(icons)try{Drawable d=r.loadIcon(c.getPackageManager());Bitmap bmp=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bmp);d.setBounds(0,0,72,72);d.draw(canvas);ByteArrayOutputStream bytes=new ByteArrayOutputStream();bmp.compress(Bitmap.CompressFormat.PNG,100,bytes);bmp.recycle();row.put("icon","data:image/png;base64,"+android.util.Base64.encodeToString(bytes.toByteArray(),android.util.Base64.NO_WRAP));}catch(Exception ignored){}
            out.put(row);
        }return out;
    }
}
