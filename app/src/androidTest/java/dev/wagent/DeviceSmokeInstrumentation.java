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
            CountDownLatch granted=new CountDownLatch(1);int[] grantResult={Activity.RESULT_CANCELED};
            getTargetContext().sendOrderedBroadcast(new Intent().setClassName("dev.wagent.test","dev.wagent.SmokeGrantReceiver"),null,new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){grantResult[0]=getResultCode();granted.countDown();}},null,0,null,null);
            require(granted.await(10,TimeUnit.SECONDS)&&grantResult[0]==Activity.RESULT_OK,"Test provider did not grant tree access");
            Uri projectTree=android.provider.DocumentsContract.buildTreeDocumentUri("dev.wagent.test.documents","root");String folder=store.addFolder(projectTree).getString("id");
            store.execute(new JSONObject().put("action","write").put("folder",folder).put("path","sample.txt").put("content","before\n"));
            Project project=new Project(app);JSONObject prepared=project.prepare(folder,"sample.txt",null,"@@ -1 +1 @@\n-before\n+after\n");JSONObject change=project.apply(prepared);require(change.getBoolean("verified"),"Project write not verified");
            require("after\n".equals(project.execute(new JSONObject().put("action","read_file").put("folder",folder).put("path","sample.txt"))),"Project patch did not change SAF file");
            project.rollback(change.getString("checkpoint"));require("before\n".equals(project.execute(new JSONObject().put("action","read_file").put("folder",folder).put("path","sample.txt"))),"Checkpoint did not restore file");
            store.execute(new JSONObject().put("action","delete").put("folder",folder).put("path","sample.txt"));store.removeFolder(folder);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2000,1200,android.graphics.Bitmap.Config.ARGB_8888);ByteArrayOutputStream photo=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,photo);bitmap.recycle();JSONObject preparedPhoto=Attachments.create("photo.png","image/png",photo.toByteArray(),false);require(preparedPhoto.getInt("width")==1600,"Photo was not resized");require("image/jpeg".equals(preparedPhoto.getString("mime")),"Photo format not normalized");
            JSONObject linux=new JSONObject(app.shell.run("printf linux-ok; cat /etc/alpine-release; pwd","",30,()->false));require(linux.getInt("exitCode")==0,"Bundled Linux failed: "+linux);require(linux.getString("stdout").contains("linux-ok"),"Linux command did not execute");
            Uri workspaceTree=android.provider.DocumentsContract.buildTreeDocumentUri(WorkspaceDocumentsProvider.AUTHORITY,"root");String workspaceFolder=store.addFolder(workspaceTree).getString("id");
            store.execute(new JSONObject().put("action","write").put("folder",workspaceFolder).put("path","workspace-smoke.txt").put("content","before\n"));
            JSONObject workspaceRead=new JSONObject(app.shell.run("cat workspace-smoke.txt","",10,()->false));require(workspaceRead.getInt("exitCode")==0&&workspaceRead.getString("stdout").equals("before\n"),"SAF and Linux workspace differ: "+workspaceRead);
            JSONObject workspaceEdit=project.apply(project.prepare(workspaceFolder,"workspace-smoke.txt",null,"@@ -1 +1 @@\n-before\n+after\n"));JSONObject workspaceAfter=new JSONObject(app.shell.run("cat workspace-smoke.txt","",10,()->false));require(workspaceAfter.getString("stdout").equals("after\n"),"Diff did not reach Linux workspace");project.rollback(workspaceEdit.getString("checkpoint"));
            File escapeLink=new File(app.shell.root(),"outside-link");android.system.Os.symlink(app.getFilesDir().getPath(),escapeLink.getPath());boolean denied=false;try{getTargetContext().getContentResolver().query(android.provider.DocumentsContract.buildDocumentUriUsingTree(workspaceTree,"root/outside-link"),null,null,null,null);}catch(Exception expected){denied=true;}finally{java.nio.file.Files.deleteIfExists(escapeLink.toPath());}require(denied,"Workspace provider followed an escaping symlink");
            store.execute(new JSONObject().put("action","delete").put("folder",workspaceFolder).put("path","workspace-smoke.txt"));store.removeFolder(workspaceFolder);
            JSONObject packages=new JSONObject(app.shell.run("pkg update && pkg install python3 nodejs npm git openssh build-base && python3 -c 'assert sum([1,2,3]) == 6; print(\"python-ok\")' && node -e 'if (2+2 !== 4) process.exit(1); console.log(\"node-ok\")' && git --version && ssh -V && echo 'int main(void){return 0;}' > smoke.c && cc smoke.c -o smoke-native && ./smoke-native","",420,()->false));require(packages.getInt("exitCode")==0,"Linux package install/runtime/compiler failed: "+packages);require(packages.getString("stdout").contains("python-ok")&&packages.getString("stdout").contains("node-ok"),"Installed language runtimes did not execute");
            String internal=app.workspace();require(!internal.isEmpty(),"Default local project missing");
            store.execute(new JSONObject().put("action","write").put("folder",internal).put("path","browser-test.html").put("content","<!doctype html><html><head><title>Background test</title></head><body style='background:white;color:black'><input id='name'><button id='test' onclick=\"document.getElementById('result').textContent='Hello '+document.getElementById('name').value\">Test</button><h1 id='result'>Ready</h1><script>console.log('browser-ready')</script></body></html>"));
            getTargetContext().startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(700);
            String foregroundBefore=new JSONObject(DeviceService.live.screen()).optString("package");
            JSONObject preview=new JSONObject(app.browser.execute(new JSONObject().put("action","preview").put("path","browser-test.html"),()->false));require(preview.getString("title").equals("Background test"),"Background browser did not load JS page");
            app.browser.execute(new JSONObject().put("action","type").put("selector","#name").put("text","W Agent"),()->false);require(new JSONObject(app.browser.execute(new JSONObject().put("action","click").put("selector","#test"),()->false)).getBoolean("ok"),"Browser click failed");
            require(new JSONObject(app.browser.execute(new JSONObject().put("action","assert").put("selector","#result").put("text","Hello W Agent"),()->false)).getBoolean("passed"),"Browser DOM assertion failed");
            require(!new JSONObject(app.browser.execute(new JSONObject().put("action","assert").put("text","missing-text"),()->false)).getBoolean("passed"),"Browser fabricated successful assertion");
            String foregroundAfter=new JSONObject(DeviceService.live.screen()).optString("package");require(foregroundBefore.equals(foregroundAfter),"Background browser switched the phone screen");
            JSONObject shot=new JSONObject(app.browser.execute(new JSONObject().put("action","screenshot"),()->false)).getJSONObject("file");require(shot.getLong("size")>1000,"Browser screenshot is empty");
            android.graphics.Bitmap pageBitmap=android.graphics.BitmapFactory.decodeFile(store.outputFile(shot.getString("id")).getPath());require(pageBitmap!=null&&pageBitmap.getWidth()==1000,"Browser screenshot dimensions wrong");int firstPixel=pageBitmap.getPixel(0,0);boolean visible=false;for(int y=0;y<Math.min(250,pageBitmap.getHeight());y+=3)for(int x=0;x<pageBitmap.getWidth();x+=3)if(pageBitmap.getPixel(x,y)!=firstPixel)visible=true;pageBitmap.recycle();require(visible,"Background browser screenshot has no rendered page content");
            require(app.browser.execute(new JSONObject().put("action","console"),()->false).contains("browser-ready"),"Browser console missing");app.browser.execute(new JSONObject().put("action","close"),()->false);
            store.execute(new JSONObject().put("action","delete").put("folder",internal).put("path","browser-test.html"));
            getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(700);
            PtySession terminal=app.terminal();terminal.open(80,24);terminal.input("stty -echo; test -t 0 && test -t 1 && echo interactive-tty-ok; stty size\n");
            deadline=SystemClock.uptimeMillis()+10000;String terminalText="";while(SystemClock.uptimeMillis()<deadline){terminalText=terminal.state(0).getString("text");if(terminalText.contains("\r\ninteractive-tty-ok\r\n")&&terminalText.contains("24 80"))break;Thread.sleep(100);}require(terminalText.contains("\r\ninteractive-tty-ok\r\n"),"PTY is not interactive: "+terminalText);require(terminalText.contains("24 80"),"PTY size missing: "+terminalText);
            terminal.resize(100,30);terminal.input("stty size\n");deadline=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<deadline&&!terminal.state(0).getString("text").contains("30 100"))Thread.sleep(100);require(terminal.state(0).getString("text").contains("30 100"),"PTY resize failed");terminal.input("exit 9\n");deadline=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<deadline&&terminal.state(0).getBoolean("running"))Thread.sleep(100);require(terminal.state(0).getInt("exitCode")==9,"PTY exit status missing: "+terminal.state(0));
            JSONObject command=new JSONObject(app.shell.run("printf terminal-ok; printf stderr-ok >&2; exit 7","",10,()->false));require(command.getInt("exitCode")==7,"Shell exit code missing");require(command.getString("stdout").contains("terminal-ok"),"Shell stdout missing");require(command.getString("stderr").contains("stderr-ok"),"Shell stderr missing");
            JSONObject timed=new JSONObject(app.shell.run("sleep 5","",1,()->false));require(timed.getBoolean("stopped"),"Shell timeout not enforced");
            app.agent.newChat();require(app.agent.state().has("chatId"),"Chat session missing");
            shell(automation,"settings put secure enabled_accessibility_services ''");Thread.sleep(600);
            require(DeviceService.live==null,"Disconnected service retained stale reference");
            shell(automation,"settings put secure enabled_accessibility_services dev.wagent/dev.wagent.DeviceService");
            deadline=SystemClock.uptimeMillis()+15000;while(DeviceService.live==null&&SystemClock.uptimeMillis()<deadline)Thread.sleep(200);
            require(DeviceService.live!=null,"Service did not reconnect after user-style toggle");
            result.putString("stream","PASS: Android service connect/disconnect, screen tree, overlay, packaged UI, file provider, chat session, SAF diff and rollback, photo normalization, terminal output and timeout, embedded Linux, Python, Node, Git, SSH and compiled C executable, interactive PTY, terminal size and exit status, Linux workspace SAF roundtrip and symlink protection, background browser JS/DOM/assert/console/screenshot without switching foreground app\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable error){StringWriter stack=new StringWriter();error.printStackTrace(new PrintWriter(stack));result.putString("stream","FAIL: "+stack);finish(Activity.RESULT_CANCELED,result);}
    }
}
