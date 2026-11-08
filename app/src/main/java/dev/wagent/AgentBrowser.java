package dev.wagent;

import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.view.View;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** A separate, offscreen WebView. No Android bridge or device-screen actions. */
public final class AgentBrowser {
    private final WApp app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private WebView browser;
    private CompletableFuture<String> loading;
    private final List<String> console=Collections.synchronizedList(new ArrayList<>());
    private final Set<String> validated=ConcurrentHashMap.newKeySet();
    private static final String PREVIEW="preview.wagent.local";
    public AgentBrowser(WApp app){this.app=app;}
    private boolean local(){return browser!=null&&browser.getUrl()!=null&&PREVIEW.equals(Uri.parse(browser.getUrl()).getHost());}
    private void create(){
        if(browser!=null)return;browser=new WebView(app);WebSettings settings=browser.getSettings();settings.setJavaScriptEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setSupportMultipleWindows(false);settings.setDomStorageEnabled(false);settings.setMediaPlaybackRequiresUserGesture(true);settings.setGeolocationEnabled(false);settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        browser.setWebChromeClient(new WebChromeClient(){@Override public boolean onConsoleMessage(ConsoleMessage message){synchronized(console){if(console.size()>=50)console.remove(0);console.add(message.messageLevel()+": "+message.message());}return true;}@Override public void onPermissionRequest(PermissionRequest request){request.deny();}@Override public void onGeolocationPermissionsShowPrompt(String origin,GeolocationPermissions.Callback callback){callback.invoke(origin,false,false);}});
        browser.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();try{
                    if(!"https".equals(uri.getScheme())||uri.getUserInfo()!=null)return denied();
                    if(PREVIEW.equals(uri.getHost())){
                        String relative=uri.getPath();if(relative==null)throw new IOException();relative=relative.replaceFirst("^/","");if(relative.isEmpty())relative="index.html";relative=FilesStore.cleanPath(relative);
                        File root=app.shell.root(),file=new File(root,relative).getCanonicalFile();if(!file.getPath().startsWith(root.getPath()+File.separator)||!file.isFile())return denied();
                        String ext=MimeTypeMap.getFileExtensionFromUrl(file.getName()),mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);if(mime==null)mime=ext.equals("js")?"application/javascript":ext.equals("html")?"text/html":"application/octet-stream";
                        Map<String,String> headers=new HashMap<>();headers.put("Content-Security-Policy","default-src 'self' https: data:; script-src 'self' 'unsafe-inline' https:; style-src 'self' 'unsafe-inline' https:; object-src 'none'; frame-src 'none'; base-uri 'self'; form-action 'self' https:");headers.put("Cache-Control","no-store");return new WebResourceResponse(mime,"UTF-8",200,"OK",headers,new FileInputStream(file));
                    }
                    String host=uri.getHost();if(host==null)return denied();if(!validated.contains(host)){WebTools.validate(uri.toString());validated.add(host);}return null;
                }catch(Exception e){return denied();}
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){Uri uri=request.getUrl();return !"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null;}
            @Override public void onPageFinished(WebView view,String url){if(loading!=null){loading.complete(url);loading=null;}}
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){if(request.isForMainFrame()&&loading!=null){loading.completeExceptionally(new IOException("Браузер: "+error.getDescription()));loading=null;}}
            @Override public void onReceivedSslError(WebView view,android.webkit.SslErrorHandler handler,android.net.http.SslError error){handler.cancel();if(loading!=null){loading.completeExceptionally(new IOException("Браузер: ошибка сертификата HTTPS"));loading=null;}}
        });resize(1000,760);
    }
    private static WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream("Resource blocked".getBytes(StandardCharsets.UTF_8)));}
    private void resize(int width,int height){browser.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));browser.layout(0,0,width,height);}
    private <T>T ui(Callable<T> work,Net.Cancel cancel)throws Exception{CompletableFuture<T> future=new CompletableFuture<>();main.post(()->{try{future.complete(work.call());}catch(Throwable e){future.completeExceptionally(e);}});return await(future,cancel,15);}
    private <T>T await(CompletableFuture<T> future,Net.Cancel cancel,int seconds)throws Exception{long end=SystemClock.uptimeMillis()+seconds*1000L;while(!future.isDone()){if(cancel.cancelled())throw new InterruptedException("Браузер остановлен");if(SystemClock.uptimeMillis()>end)throw new IOException("Браузер: превышено время ожидания");Thread.sleep(80);}try{return future.get();}catch(ExecutionException e){Throwable cause=e.getCause();throw new IOException(cause==null?e.getMessage():cause.getMessage(),cause);}}
    private String js(String script,Net.Cancel cancel)throws Exception{CompletableFuture<String> future=new CompletableFuture<>();main.post(()->{try{create();browser.evaluateJavascript(script,future::complete);}catch(Throwable e){future.completeExceptionally(e);}});String raw=await(future,cancel,15);Object value=new JSONTokener(raw).nextValue();if(value==JSONObject.NULL)throw new IOException("Страница не вернула результат");return String.valueOf(value);}
    private static final String SNAPSHOT="JSON.stringify({url:location.href,title:document.title,text:(document.body?.innerText||'').slice(0,24000),elements:Array.from(document.querySelectorAll('a,button,input,textarea,select,[role=button]')).filter(e=>e.getBoundingClientRect().width&&e.getBoundingClientRect().height).slice(0,100).map((e,i)=>{e.setAttribute('data-wa-id',String(i));return {id:i,tag:e.tagName,text:(e.innerText||e.getAttribute('aria-label')||e.getAttribute('placeholder')||'').slice(0,160),type:e.type||'',href:e.getAttribute('href')||''}})})";
    public synchronized String execute(JSONObject a,Net.Cancel cancel)throws Exception{
        String action=a.getString("action");
        if(action.equals("open")||action.equals("preview")){
            String url;if(action.equals("preview")){String path=FilesStore.cleanPath(a.optString("path","index.html"));File root=app.shell.root(),file=new File(root,path).getCanonicalFile();if(!file.getPath().startsWith(root.getPath()+File.separator)||!file.isFile())throw new IOException("HTML-файл не найден внутри /workspace");url="https://"+PREVIEW+"/"+Uri.encode(path,"/");}else{url=a.getString("url");WebTools.validate(url);}
            final String target=url;CompletableFuture<String> loaded=new CompletableFuture<>();ui(()->{create();if(loading!=null)loading.completeExceptionally(new IOException("Загрузка заменена"));loading=loaded;console.clear();resize(Math.max(320,Math.min(1600,a.optInt("width",1000))),Math.max(320,Math.min(1600,a.optInt("height",760))));browser.loadUrl(target);return true;},cancel);await(loaded,cancel,30);return js(SNAPSHOT,cancel);
        }
        if(action.equals("close")){ui(()->{if(browser!=null){browser.stopLoading();browser.destroy();browser=null;}return true;},cancel);return "Браузер закрыт";}
        ui(()->{if(browser==null)throw new IOException("Сначала browser open или preview");return true;},cancel);
        if(action.equals("wait")){long until=SystemClock.uptimeMillis()+Math.max(0,Math.min(5000,a.optInt("ms",500)));while(SystemClock.uptimeMillis()<until){if(cancel.cancelled())throw new InterruptedException("Браузер остановлен");Thread.sleep(80);}return js(SNAPSHOT,cancel);}
        if(action.equals("snapshot"))return js(SNAPSHOT,cancel);
        if(action.equals("assert")){String selector=a.optString("selector"),text=a.optString("text");if(selector.isEmpty()&&text.isEmpty())throw new IOException("Нужен selector или ожидаемый text");String result=js("(()=>{try{const e="+(selector.isEmpty()?"document.body":"document.querySelector("+JSONObject.quote(selector)+")")+";const ok=!!e"+(text.isEmpty()?"":" && (e.innerText||e.value||'').includes("+JSONObject.quote(text)+")")+";return JSON.stringify({passed:ok,selector:"+JSONObject.quote(selector)+",expected:"+JSONObject.quote(text)+"})}catch(e){return JSON.stringify({passed:false,error:e.message})}})()",cancel);JSONObject report=new JSONObject(result);if(!report.optBoolean("passed"))report.put("notice","Проверка не пройдена; исправь причину и проверь снова");return report.toString();}
        if(action.equals("click")||action.equals("type")){
            boolean preview=ui(this::local,cancel);if(!preview&&!MainActivity.approve("Действие в фоновом браузере?",action+" · "+a.optString("selector")))return "Действие отклонено";
            String selector=a.optString("selector");if(selector.isEmpty())selector="[data-wa-id='"+a.getInt("id")+"']";String code="(()=>{try{const e=document.querySelector("+JSONObject.quote(selector)+");if(!e)return JSON.stringify({ok:false,error:'Элемент не найден'});";
            if(action.equals("type"))code+="e.focus();const p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;const setter=Object.getOwnPropertyDescriptor(p,'value')?.set;if(!setter)return JSON.stringify({ok:false,error:'Элемент не поддерживает ввод'});setter.call(e,"+JSONObject.quote(a.getString("text"))+");e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));";
            else code+="e.click();";code+="return JSON.stringify({ok:true})}catch(e){return JSON.stringify({ok:false,error:e.message})}})()";return js(code,cancel);
        }
        if(action.equals("console"))return ui(()->new JSONArray(new ArrayList<>(console)).toString(),cancel);
        if(action.equals("screenshot")){
            byte[] png=ui(()->{Bitmap bitmap=Bitmap.createBitmap(browser.getWidth(),browser.getHeight(),Bitmap.Config.ARGB_8888);browser.draw(new Canvas(bitmap));ByteArrayOutputStream bytes=new ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes);bitmap.recycle();return bytes.toByteArray();},cancel);
            JSONObject file=new FilesStore(app).importOutput("browser.png","image/png",new ByteArrayInputStream(png),10*1024*1024,cancel);app.agent.outputEvent(file);return new JSONObject().put("file",file).put("notice","Снимок отдельного браузера; экран телефона не использовался").toString();
        }
        throw new IOException("Неизвестное действие browser");
    }
}
