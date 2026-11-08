package dev.wagent;
import android.app.Application;
public class WApp extends Application {
    public Vault vault;
    public Agent agent;
    public Shell shell;
    public AgentBrowser browser;
    public synchronized String workspace()throws Exception{FilesStore store=new FilesStore(this);org.json.JSONObject folder=store.addFolder(android.provider.DocumentsContract.buildTreeDocumentUri(WorkspaceDocumentsProvider.AUTHORITY,"root"));if(vault.get().optString("projectFolder").isEmpty())vault.patch("projectFolder",folder.getString("id"));return folder.getString("id");}
    public volatile PtySession pty;
    public synchronized PtySession terminal(){if(pty==null)pty=new PtySession(this);return pty;}
    @Override public void onCreate() { super.onCreate(); vault=new Vault(this); shell=new Shell(this);browser=new AgentBrowser(this); agent=new Agent(this); }
}
