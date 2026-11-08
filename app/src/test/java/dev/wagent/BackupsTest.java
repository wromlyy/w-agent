package dev.wagent;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class BackupsTest {
    private JSONObject state()throws Exception{return new JSONObject().put("profiles",new JSONArray().put(new JSONObject().put("id","p").put("base","https://api.test").put("key","secret-test"))).put("folders",new JSONArray().put("private-grant")).put("history",new JSONArray().put("private-history"));}
    @Test public void plainBackupExcludesSecretsAndDeviceGrants()throws Exception{String text=Backups.export(state(),false,"").toString();assertFalse(text.contains("secret-test"));assertFalse(text.contains("private-grant"));assertFalse(text.contains("private-history"));assertEquals(1,Backups.decode(text,"").getJSONArray("profiles").length());}
    @Test public void encryptedBackupAuthenticatesPasswordAndContents()throws Exception{JSONObject box=Backups.export(state(),true,"password-long");assertFalse(box.toString().contains("secret-test"));assertEquals("secret-test",Backups.decode(box.toString(),"password-long").getJSONArray("profiles").getJSONObject(0).getString("key"));assertThrows(IllegalArgumentException.class,()->Backups.decode(box.toString(),"wrong-password"));}
}
