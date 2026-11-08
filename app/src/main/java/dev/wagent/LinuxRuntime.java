package dev.wagent;

import android.net.*;
import android.os.Build;
import android.system.Os;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** An APK-bundled PRoot/Alpine environment. No external app, root or shell UID. */
public final class LinuxRuntime {
    private static final Object INSTALL_LOCK=new Object();
    private final WApp app;
    public LinuxRuntime(WApp app){this.app=app;}
    private String abi()throws IOException{for(String abi:Build.SUPPORTED_ABIS)if(abi.equals("arm64-v8a")||abi.equals("x86_64"))return abi;throw new IOException("Linux-окружение требует arm64-v8a или x86_64");}
    private File base(){return new File(app.getFilesDir(),"linux");}
    private File root(){return new File(base(),"rootfs");}
    private File nativeFile(String name){return new File(app.getApplicationInfo().nativeLibraryDir,name);}
    public JSONObject status()throws JSONException{
        String arch="";String error="";try{arch=abi();}catch(IOException e){error=e.getMessage();}
        return new JSONObject().put("available",!arch.isEmpty()&&nativeFile("libwproot.so").isFile()).put("installed",new File(base(),"ready").isFile()).put("abi",arch).put("error",error).put("distribution","Alpine 3.22.8");
    }
    private static void write(File file,String text)throws IOException{File parent=file.getParentFile();if(parent!=null&&!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Не удалось создать папку Linux");try(FileOutputStream out=new FileOutputStream(file)){out.write(text.getBytes(StandardCharsets.UTF_8));}}
    private void dns()throws IOException{
        StringBuilder nameservers=new StringBuilder();try{ConnectivityManager manager=(ConnectivityManager)app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE);LinkProperties link=manager.getLinkProperties(manager.getActiveNetwork());if(link!=null)for(java.net.InetAddress server:link.getDnsServers())nameservers.append("nameserver ").append(server.getHostAddress()).append('\n');}catch(Exception ignored){}
        if(nameservers.length()==0)nameservers.append("nameserver 1.1.1.1\nnameserver 8.8.8.8\n");write(new File(root(),"etc/resolv.conf"),nameservers.toString());
    }
    public void install()throws Exception{synchronized(INSTALL_LOCK){installLocked();}}
    private void installLocked()throws Exception{
        if(new File(base(),"ready").isFile()){nativeLinks();dns();return;}
        String abi=abi();if(!nativeFile("libwproot.so").isFile())throw new IOException("В этой сборке нет встроенного Linux. Нужен APK с runtime для "+abi);
        if(!base().isDirectory()&&!base().mkdirs())throw new IOException("Не удалось создать окружение");
        File archive=new File(base(),"rootfs.tar.gz");MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=app.getAssets().open("linux/"+abi+".rootfs");FileOutputStream out=new FileOutputStream(archive)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){digest.update(b,0,n);out.write(b,0,n);}}
        JSONObject meta;try(InputStream in=app.getAssets().open("linux/"+abi+".json")){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)out.write(b,0,n);meta=new JSONObject(out.toString("UTF-8"));}
        StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format(Locale.ROOT,"%02x",b&255));if(!actual.toString().equals(meta.getString("sha256")))throw new IOException("Контрольная сумма Linux не совпала");
        File staging=new File(base(),"installing");remove(staging);if(!staging.mkdirs())throw new IOException("Не удалось распаковать Linux");
        ProcessBuilder pb=new ProcessBuilder("/system/bin/toybox","tar","-xzf",archive.getPath(),"-C",staging.getPath());pb.environment().clear();pb.environment().put("PATH","/system/bin");pb.redirectErrorStream(true);Process process=pb.start();
        // Drain diagnostics concurrently so an error cannot fill the pipe and deadlock extraction.
        ByteArrayOutputStream errors=new ByteArrayOutputStream();Thread drain=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] b=new byte[2048];int n;while((n=input.read(b))!=-1)if(errors.size()<8192)errors.write(b,0,Math.min(n,8192-errors.size()));}catch(IOException ignored){}});drain.start();
        if(!process.waitFor(90,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Таймаут распаковки Linux");}drain.join(2000);if(process.exitValue()!=0)throw new IOException("Распаковка Linux: "+errors.toString("UTF-8"));
        remove(root());if(!staging.renameTo(root()))throw new IOException("Не удалось завершить установку Linux");
        for(String dir:new String[]{"workspace","proc","dev","sys","tmp","root"})new File(root(),dir).mkdirs();
        write(new File(root(),"etc/apk/repositories"),"https://dl-cdn.alpinelinux.org/alpine/v3.22/main\nhttps://dl-cdn.alpinelinux.org/alpine/v3.22/community\n");
        write(new File(root(),"usr/local/bin/pkg"),"#!/bin/sh\n# W Agent compatibility helper; packages come from Alpine, not Termux.\ncase \"$1\" in\n install|add) shift; exec apk add \"$@\";;\n uninstall|remove) shift; exec apk del \"$@\";;\n update) shift; exec apk update \"$@\";;\n upgrade) shift; exec apk upgrade \"$@\";;\n search) shift; exec apk search \"$@\";;\n list-installed) shift; exec apk info \"$@\";;\n *) echo 'pkg: install, uninstall, update, upgrade, search, list-installed (Alpine apk)'; exit 2;;\nesac\n");new File(root(),"usr/local/bin/pkg").setExecutable(true,true);
        nativeLinks();
        dns();write(new File(base(),"ready"),meta.getString("version"));if(!archive.delete())archive.deleteOnExit();
    }
    private void nativeLinks()throws Exception{
        File links=new File(base(),"lib");links.mkdirs();File talloc=new File(links,"libtalloc.so.2"),source=nativeFile("libtalloc.so");
        // PackageManager changes nativeLibraryDir on upgrades; never retain the old install path.
        if(!talloc.getCanonicalFile().equals(source.getCanonicalFile())){Files.deleteIfExists(talloc.toPath());Os.symlink(source.getPath(),talloc.getPath());}
    }
    private static void remove(File file)throws IOException{if(!file.exists()&&!Files.isSymbolicLink(file.toPath()))return;if(!Files.isSymbolicLink(file.toPath())&&file.isDirectory()){File[] children=file.listFiles();if(children==null)throw new IOException("Нет доступа к временным файлам");for(File child:children)remove(child);}if(!file.delete())throw new IOException("Не удалось удалить временный файл Linux");}
    public ProcessBuilder command(String cmd,File workspace,String cwd)throws Exception{
        install();File temp=new File(base(),"tmp");temp.mkdirs();String working=cwd==null||cwd.isEmpty()?"/workspace":"/workspace/"+FilesStore.cleanPath(cwd);
        ProcessBuilder pb=new ProcessBuilder(nativeFile("libwproot.so").getPath(),"--link2symlink","-0","-r",root().getPath(),"-b","/dev","-b","/proc","-b",workspace.getPath()+":/workspace","-w",working,"/usr/bin/env","-i","HOME=/root","PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","LANG=C.UTF-8","TERM=xterm-256color","TMPDIR=/tmp","/bin/sh","-c",cmd);
        Map<String,String> env=pb.environment();env.clear();env.put("PATH","/system/bin");env.put("LD_LIBRARY_PATH",new File(base(),"lib").getPath()+":"+app.getApplicationInfo().nativeLibraryDir);env.put("PROOT_LOADER",nativeFile("libwloader.so").getPath());env.put("PROOT_TMP_DIR",temp.getPath());env.put("PROOT_NO_SECCOMP","1");pb.directory(workspace);return pb;
    }
}
