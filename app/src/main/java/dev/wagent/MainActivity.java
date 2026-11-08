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
    private WebView web;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private String pickerId,pickerMode;
    private JSONObject exportData;
    private WApp app(){return (WApp)getApplication();}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        web=new WebView(this);web.setBackgroundColor(Color.rgb(245,244,240));setContentView(web);
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
    @Override protected void onResume(){super.onResume();if(web!=null)web.onResume();}
    @Override protected void onPause(){if(web!=null)web.onPause();super.onPause();}
    @Override protected void onDestroy(){io.shutdownNow();if(web!=null){web.removeJavascriptInterface("WNative");web.destroy();web=null;}super.onDestroy();}
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
                    case "init":result=new JSONObject().put("settings",app().vault.get()).put("accessibility",DeviceService.live!=null).put("run",app().agent.state()).put("version","0.1.0");break;
                    case "state":result=new JSONObject().put("run",app().agent.state()).put("accessibility",DeviceService.live!=null);break;
                    case "save":app().vault.updateSettings(args);result=true;break;
                    case "run":app().agent.start(args.getString("task"),args.optJSONArray("files"));result=true;break;
                    case "stop":app().agent.stop();result=true;break;
                    case "models":result=Models.models(args,()->false);break;
                    case "testService":result=Connections.execute(args,new JSONObject().put("method","GET").put("path","api.github.com".equals(Uri.parse(args.optString("url")).getHost())?"user":"").put("operation","tools/list"),()->false);break;
                    case "apps":result=apps(MainActivity.this,true);break;
                    case "accessibility":handler.post(()->{new AlertDialog.Builder(MainActivity.this).setTitle("Управление телефоном").setMessage("Во время задачи W Agent сможет читать открытый экран, нажимать кнопки и вводить текст. Текст экрана и, если вы включите снимки, изображения отправляются выбранному поставщику модели. Включите W Agent в разделе скачанных служб. Остановить задачу можно кнопкой W поверх приложений.").setNegativeButton("Отмена",null).setPositiveButton("Открыть настройки",(d,w)->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();});result=true;break;
                    case "notifications":handler.post(()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);});result=true;break;
                    case "pick":{String callback=id,mode=args.optString("mode","files");handler.post(()->pick(callback,mode));return;}
                    case "export":{exportData=app().vault.get();String callback=id;handler.post(()->{pickerId=callback;pickerMode="export";Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"w-agent-history.json");startActivityForResult(i,51);});return;}
                    case "clearHistory":{JSONObject next=app().vault.get().put("history",new JSONArray());app().vault.save(next);result=true;break;}
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
            if("export".equals(mode)){
                try(OutputStream out=getContentResolver().openOutputStream(data.getData())){if(out==null)throw new IOException("Файл недоступен");out.write(new JSONObject().put("app","W Agent").put("history",exportData.getJSONArray("history")).toString(2).getBytes(StandardCharsets.UTF_8));}exportData=null;reply(id,true,null);return;
            }
            List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<Math.min(6,data.getClipData().getItemCount());i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());
            JSONArray files=new JSONArray();int total=0;
            for(Uri uri:uris){String name="file";try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
                byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Не удалось прочитать файл");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1){if(out.size()+n>4*1024*1024)throw new IOException("Максимум 4 МБ на файл");out.write(chunk,0,n);}bytes=out.toByteArray();}
                total+=bytes.length;if(total>10*1024*1024)throw new IOException("Общий размер файлов не должен превышать 10 МБ");
                String mime=getContentResolver().getType(uri);if(mime==null)mime="application/octet-stream";
                JSONObject file=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("mime",mime).put("size",bytes.length);
                if("skill".equals(mode)||mime.startsWith("text/")||mime.equals("application/json")||name.matches("(?i).+\\.(md|txt|json|csv|js|ts|java|py|xml|html|css|yaml|yml|log|sh)$")){
                    if(bytes.length>200000)throw new IOException("Текстовый файл: максимум 200 КБ");file.put("text",new String(bytes,StandardCharsets.UTF_8));
                }else if(mime.startsWith("image/")||mime.equals("application/pdf"))file.put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
                else throw new IOException("Поддерживаются текстовые файлы, код, изображения и PDF");
                files.put(file);
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
