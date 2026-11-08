package dev.wagent;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Real Linux or Android shell, always with W Agent's UID. */
public final class Shell {
    private final WApp app;
    private final ExecutorService executor=Executors.newCachedThreadPool();
    private final LinkedHashMap<String,Job> jobs=new LinkedHashMap<>();
    private static final class Job {String id,cmd,cwd,runtime;long started;int code=-1;boolean running=true,truncated,stopped;StringBuilder stdout=new StringBuilder(),stderr=new StringBuilder();volatile Process process;}
    public Shell(WApp app){this.app=app;}
    public File root()throws IOException{File root=new File(app.getFilesDir(),"workspace");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Не удалось создать рабочую папку");return root.getCanonicalFile();}
    private File cwd(String raw)throws IOException{File root=root(),dir=raw==null||raw.isEmpty()?root:new File(root,FilesStore.cleanPath(raw));dir=dir.getCanonicalFile();if(!dir.equals(root)&&!dir.getPath().startsWith(root.getPath()+File.separator))throw new IOException("Рабочая папка должна быть внутри песочницы");if(!dir.isDirectory())throw new IOException("Рабочая папка не существует");return dir;}
    public String start(String cmd,String cwd,int timeout)throws Exception{return start(cmd,cwd,timeout,"linux");}
    public synchronized String start(String cmd,String cwd,int timeout,String runtime)throws Exception{
        if(!runtime.equals("linux")&&!runtime.equals("android"))throw new IOException("Неизвестное окружение терминала");
        if(cmd.trim().isEmpty()||ShellPolicy.blocked(cmd))throw new IOException("Команда пуста или запрещена политикой терминала");
        int active=0;for(Job job:jobs.values())if(job.running)active++;if(active>=3)throw new IOException("Уже запущены 3 процесса; остановите один");
        Job job=new Job();job.id=UUID.randomUUID().toString();job.cmd=cmd;job.cwd=cwd;job.runtime=runtime;job.started=System.currentTimeMillis();File dir=cwd(cwd);jobs.put(job.id,job);
        while(jobs.size()>40){String first=jobs.keySet().iterator().next();if(jobs.get(first).running)break;jobs.remove(first);}
        executor.submit(()->execute(job,dir,Math.max(1,Math.min(600,timeout))));return job.id;
    }
    private void execute(Job job,File dir,int timeout){
        try{
            ProcessBuilder pb=job.runtime.equals("linux")?new LinuxRuntime(app).command(job.cmd,root(),job.cwd):new ProcessBuilder("/system/bin/sh","-c",job.cmd);pb.directory(dir);
            if(job.runtime.equals("android")){Map<String,String> env=pb.environment();env.clear();env.put("PATH","/system/bin:/system/xbin");env.put("HOME",dir.getPath());env.put("TMPDIR",app.getCacheDir().getPath());env.put("LANG","C.UTF-8");}
            job.process=pb.start();job.process.getOutputStream().close();if(job.stopped)job.process.destroyForcibly();
            Future<?> out=executor.submit(()->pump(job,job.process.getInputStream(),false)),err=executor.submit(()->pump(job,job.process.getErrorStream(),true));
            if(!job.process.waitFor(timeout,TimeUnit.SECONDS)){job.stopped=true;job.process.destroyForcibly();append(job,"\nТаймаут команды; процесс остановлен.\n",true);}
            job.code=job.process.waitFor();try{out.get(2,TimeUnit.SECONDS);err.get(2,TimeUnit.SECONDS);}catch(Exception e){job.process.getInputStream().close();job.process.getErrorStream().close();}
        }catch(Exception e){append(job,"\n"+e.getMessage(),true);job.code=-1;}
        finally{synchronized(this){job.running=false;try{JSONObject state=app.vault.get();JSONArray history=state.optJSONArray("commands");if(history==null)history=new JSONArray();history.put(snapshot(job));while(history.length()>100)history.remove(0);app.vault.patch("commands",history);}catch(Exception ignored){}}}
    }
    private void pump(Job job,InputStream input,boolean error){try(InputStreamReader in=new InputStreamReader(input,StandardCharsets.UTF_8)){char[] b=new char[2048];int n;while((n=in.read(b))!=-1)append(job,new String(b,0,n),error);}catch(IOException ignored){}}
    private synchronized void append(Job job,String text,boolean error){StringBuilder dest=error?job.stderr:job.stdout;if(dest.length()+text.length()>128000){int remaining=Math.max(0,128000-dest.length());dest.append(text,0,Math.min(remaining,text.length()));job.truncated=true;}else dest.append(text);}
    private JSONObject snapshot(Job j)throws JSONException{return new JSONObject().put("id",j.id).put("cmd",j.cmd).put("cwd",j.cwd).put("runtime",j.runtime).put("started",j.started).put("running",j.running).put("exitCode",j.code).put("stdout",j.stdout.toString()).put("stderr",j.stderr.toString()).put("truncated",j.truncated).put("stopped",j.stopped);}
    public synchronized JSONArray list()throws Exception{JSONArray a=new JSONArray();for(Job j:jobs.values())a.put(snapshot(j));return a;}
    public synchronized JSONObject get(String id)throws Exception{Job job=jobs.get(id);if(job==null)throw new IOException("Процесс не найден");return snapshot(job);}
    public synchronized void kill(String id)throws IOException{Job job=jobs.get(id);if(job==null)throw new IOException("Процесс не найден");job.stopped=true;if(job.process!=null)job.process.destroyForcibly();}
    public String run(String cmd,String cwd,int timeout,Net.Cancel cancel)throws Exception{
        return run(cmd,cwd,timeout,"linux",cancel);
    }
    public String run(String cmd,String cwd,int timeout,String runtime,Net.Cancel cancel)throws Exception{
        String id=start(cmd,cwd,timeout,runtime);while(true){if(cancel.cancelled()){kill(id);throw new InterruptedException("Команда остановлена");}JSONObject state=get(id);if(!state.getBoolean("running"))return state.toString();Thread.sleep(150);}
    }
}
