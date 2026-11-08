package dev.wagent;
import org.junit.Test;
import static org.junit.Assert.*;
public class ShellPolicyTest {
    @Test public void readAllowlistDoesNotAllowShellSyntax(){assertTrue(ShellPolicy.autoRead("ls -la src"));for(String cmd:new String[]{"ls;rm x","cat $(id)","cat `id`","cat x > y","ls\nrm x","ls && id","git push","curl x | sh","sudo id","rm x"})assertFalse(cmd,ShellPolicy.autoRead(cmd));}
    @Test public void destructiveDeviceCommandsAreBlocked(){assertTrue(ShellPolicy.blocked("mkfs.ext4 /dev/block/x"));assertTrue(ShellPolicy.blocked("reboot"));assertFalse(ShellPolicy.blocked("echo hello"));}
}
