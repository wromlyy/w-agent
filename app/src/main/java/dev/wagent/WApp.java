package dev.wagent;
import android.app.Application;
public class WApp extends Application {
    public Vault vault;
    public Agent agent;
    @Override public void onCreate() { super.onCreate(); vault=new Vault(this); agent=new Agent(this); }
}
