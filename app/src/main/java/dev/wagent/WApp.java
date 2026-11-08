package dev.wagent;
import android.app.Application;
public class WApp extends Application {
    public Vault vault;
    public Agent agent;
    public Shell shell;
    @Override public void onCreate() { super.onCreate(); vault=new Vault(this); shell=new Shell(this); agent=new Agent(this); }
}
