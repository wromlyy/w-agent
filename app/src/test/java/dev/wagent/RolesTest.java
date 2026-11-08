package dev.wagent;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RolesTest {
    @Test public void reviewerCannotWriteEvenIfModelRequestsIt()throws Exception{JSONObject role=Roles.active(new JSONObject().put("activeRole","reviewer"));assertTrue(Roles.permits(role,"project",new JSONObject().put("action","read_file")));assertFalse(Roles.permits(role,"project",new JSONObject().put("action","write_file")));assertFalse(Roles.permits(role,"shell_exec",new JSONObject().put("cmd","git push")));assertFalse(Roles.permits(role,"device",new JSONObject().put("action","tap")));assertFalse(Roles.permits(role,"service",new JSONObject().put("operation","tools/call")));}
    @Test public void profilesValidateAndUnknownToolsAreRejected()throws Exception{JSONArray roles=Roles.presets();Roles.validate(roles);roles.getJSONObject(0).getJSONArray("tools").put("bypass");assertThrows(IllegalArgumentException.class,()->Roles.validate(roles));}
}
