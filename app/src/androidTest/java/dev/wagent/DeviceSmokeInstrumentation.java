package dev.wagent;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.WebView;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Runs only on CI's disposable emulator, not on a user's phone. */
public final class DeviceSmokeInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    private void shell(UiAutomation automation,String command)throws Exception{try(ParcelFileDescriptor fd=automation.executeShellCommand(command);InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] b=new byte[4096];while(in.read(b)!=-1){}}}
    private void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    @Override public void onStart(){
        Bundle result=new Bundle();UiAutomation automation=null;
        try{
            automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            Activity activity=startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            shell(automation,"settings put secure enabled_accessibility_services dev.wagent/dev.wagent.DeviceService");
            shell(automation,"settings put secure accessibility_enabled 1");
            long deadline=SystemClock.uptimeMillis()+20000;while(DeviceService.live==null&&SystemClock.uptimeMillis()<deadline)Thread.sleep(200);
            require(DeviceStatus.enabled(getTargetContext()),"Permission switch not detected");
            require(DeviceService.live!=null,"Accessibility service did not connect: "+DeviceStatus.get(getTargetContext()));
            JSONObject screen=new JSONObject(DeviceService.live.screen());require(screen.has("elements"),"Screen tree missing");
            DeviceService.live.showStop();Thread.sleep(700);require(DeviceService.live!=null,"Service crashed showing overlay");DeviceService.live.hideStop();
            CountDownLatch loaded=new CountDownLatch(1);String[] title={""};
            for(int attempt=0;attempt<20&&loaded.getCount()>0;attempt++){
                runOnMainSync(()->{ViewGroup content=activity.findViewById(android.R.id.content);WebView web=(WebView)content.getChildAt(0);web.evaluateJavascript("document.querySelector('.intro h1')?.textContent || ''",value->{title[0]=value;if(value.contains("Что сделать"))loaded.countDown();});});Thread.sleep(250);
            }
            require(loaded.getCount()==0,"Packaged WebView did not render: "+title[0]);
            WApp app=(WApp)getTargetContext().getApplicationContext();FilesStore store=new FilesStore(app);JSONObject file=store.output("smoke.txt","W Agent file smoke");
            Uri output=Uri.parse("content://dev.wagent.files/output/"+file.getString("id"));
            try(InputStream in=getTargetContext().getContentResolver().openInputStream(output)){require(in!=null,"Shared file provider returned null");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);require("W Agent file smoke".equals(bytes.toString("UTF-8")),"Shared file contents differ");}
            Uri projectTree=android.provider.DocumentsContract.buildTreeDocumentUri("dev.wagent.test.documents","root");String folder=store.addFolder(projectTree).getString("id");
            store.execute(new JSONObject().put("action","write").put("folder",folder).put("path","sample.txt").put("content","before\n"));
            Project project=new Project(app);JSONObject prepared=project.prepare(folder,"sample.txt",null,"@@ -1 +1 @@\n-before\n+after\n");JSONObject change=project.apply(prepared);require(change.getBoolean("verified"),"Project write not verified");
            require("after\n".equals(project.execute(new JSONObject().put("action","read_file").put("folder",folder).put("path","sample.txt"))),"Project patch did not change SAF file");
            project.rollback(change.getString("checkpoint"));require("before\n".equals(project.execute(new JSONObject().put("action","read_file").put("folder",folder).put("path","sample.txt"))),"Checkpoint did not restore file");
            store.execute(new JSONObject().put("action","delete").put("folder",folder).put("path","sample.txt"));store.removeFolder(folder);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2000,1200,android.graphics.Bitmap.Config.ARGB_8888);ByteArrayOutputStream photo=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,photo);bitmap.recycle();JSONObject preparedPhoto=Attachments.create("photo.png","image/png",photo.toByteArray(),false);require(preparedPhoto.getInt("width")==1600,"Photo was not resized");require("image/jpeg".equals(preparedPhoto.getString("mime")),"Photo format not normalized");
            JSONObject command=new JSONObject(app.shell.run("printf terminal-ok; printf stderr-ok >&2; exit 7","",10,()->false));require(command.getInt("exitCode")==7,"Shell exit code missing");require(command.getString("stdout").contains("terminal-ok"),"Shell stdout missing");require(command.getString("stderr").contains("stderr-ok"),"Shell stderr missing");
            JSONObject timed=new JSONObject(app.shell.run("sleep 5","",1,()->false));require(timed.getBoolean("stopped"),"Shell timeout not enforced");
            app.agent.newChat();require(app.agent.state().has("chatId"),"Chat session missing");
            shell(automation,"settings put secure enabled_accessibility_services ''");Thread.sleep(600);
            require(DeviceService.live==null,"Disconnected service retained stale reference");
            shell(automation,"settings put secure enabled_accessibility_services dev.wagent/dev.wagent.DeviceService");
            deadline=SystemClock.uptimeMillis()+15000;while(DeviceService.live==null&&SystemClock.uptimeMillis()<deadline)Thread.sleep(200);
            require(DeviceService.live!=null,"Service did not reconnect after user-style toggle");
            result.putString("stream","PASS: Android service connect/disconnect, screen tree, overlay, packaged UI, file provider, chat session, SAF diff and rollback, photo normalization, terminal output and timeout\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));result.putString("stream","FAIL: "+stack);finish(Activity.RESULT_CANCELED,result);}
    }
}
