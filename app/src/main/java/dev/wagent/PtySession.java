package dev.wagent;

import android.content.Intent;
import android.os.ParcelFileDescriptor;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Interactive terminal used by the person at the keyboard, never a model tool. */
public final class PtySession {
    static {System.loadLibrary("wpty");}
    private static native int[] spawn(String[] args,String[] environment,String cwd,int cols,int rows)throws IOException;
    private static native void resizeNative(int fd,int cols,int rows);
    private static native void killNative(int pid);
    private static native int waitNative(int pid);
    private final WApp app;
    private final ExecutorService io=Executors.newCachedThreadPool();
    private final StringBuilder output=new StringBuilder();
    private ParcelFileDescriptor master;
    private OutputStream input;
    private int pid,exitCode=-1;
    private boolean running;
    private long base;
    public PtySession(WApp app){this.app=app;}
    public synchronized JSONObject open(int cols,int rows)throws Exception{
        if(running)return state(0);
        ProcessBuilder command=new LinuxRuntime(app).command("exec /bin/sh -il",app.shell.root(),"");
        String[] env=command.environment().entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toArray(String[]::new);
        int[] process=spawn(command.command().toArray(new String[0]),env,command.directory().getPath(),clamp(cols),clamp(rows));
        master=ParcelFileDescriptor.adoptFd(process[0]);pid=process[1];input=new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(master.getFileDescriptor()));
        InputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(master.getFileDescriptor()));running=true;exitCode=-1;output.setLength(0);base=0;
        try{app.startForegroundService(new Intent(app,TerminalService.class));}catch(Exception e){killNative(pid);master.close();input.close();stream.close();running=false;throw e;}
        Future<?> readerTask=io.submit(()->{try(Reader reader=new InputStreamReader(stream,StandardCharsets.UTF_8)){char[] b=new char[4096];int n;while((n=reader.read(b))!=-1)append(new String(b,0,n));}catch(IOException ignored){}});
        final int child=pid;io.submit(()->{int code=waitNative(child);try{readerTask.get(2,TimeUnit.SECONDS);}catch(Exception ignored){}synchronized(this){if(pid==child){exitCode=code;running=false;try{input.close();master.close();}catch(IOException ignored){}app.stopService(new Intent(app,TerminalService.class));}}});
        return state(0);
    }
    private static int clamp(int n){return Math.max(2,Math.min(500,n));}
    private synchronized void append(String text){output.append(text);if(output.length()>524288){int drop=output.length()-524288;output.delete(0,drop);base+=drop;}}
    public synchronized JSONObject state(long cursor)throws JSONException{long start=Math.max(base,Math.min(base+output.length(),cursor));int offset=(int)(start-base),end=Math.min(output.length(),offset+65536);return new JSONObject().put("running",running).put("exitCode",exitCode).put("text",output.substring(offset,end)).put("cursor",base+end).put("dropped",cursor<base).put("pid",pid);}
    public synchronized void input(String text)throws IOException{if(!running)throw new IOException("Терминал остановлен");if(text.length()>65536)throw new IOException("Ввод длиннее 64 КБ");input.write(text.getBytes(StandardCharsets.UTF_8));input.flush();}
    public synchronized void resize(int cols,int rows){if(running)resizeNative(master.getFd(),clamp(cols),clamp(rows));}
    public synchronized void stop(){if(running)killNative(pid);}
}
