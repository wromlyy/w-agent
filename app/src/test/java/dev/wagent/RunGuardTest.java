package dev.wagent;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RunGuardTest {
    @Test public void repeatsUseCanonicalArgumentsAndResetOnlyOnProgress()throws Exception{
        RunGuard g=new RunGuard(10,60,4096,0);JSONObject a=new JSONObject("{\"id\":2,\"action\":\"tap\"}"),b=new JSONObject("{\"action\":\"tap\",\"id\":2}");
        assertNull(g.action("device",a));assertNull(g.action("device",b));assertNotNull(g.action("device",a));assertEquals(2,g.actions());g.progress();assertNull(g.action("device",a));
    }
    @Test public void budgetsEnforcedBetweenCalls()throws Exception{
        RunGuard g=new RunGuard(1,10,1024,0);g.check(9999);assertThrows(RunGuard.Limit.class,()->g.check(10000));g.account(1024);assertThrows(RunGuard.Limit.class,()->g.check(1));
        assertNull(g.action("files",new JSONObject()));assertThrows(RunGuard.Limit.class,()->g.action("device",new JSONObject()));
    }
}
