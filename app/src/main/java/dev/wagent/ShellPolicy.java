package dev.wagent;
import java.util.*;
import java.util.regex.*;

/** A convenience policy, never a claim that arbitrary shell text is sandboxed. */
public final class ShellPolicy {
    public static boolean blocked(String cmd){return cmd.indexOf('\0')>=0||cmd.length()>20000||Pattern.compile("(?i)(^|[\\s;/])(reboot|shutdown|mkfs(?:\\.[a-z0-9]+)?|fastboot|flash_image)([\\s;]|$)|/dev/block|:\\s*\\(\\s*\\)\\s*\\{").matcher(cmd).find();}
    public static boolean autoRead(String cmd){
        String[] parts=cmd.trim().split("\\s+");if(parts.length==0)return false;
        if(!Arrays.asList("pwd","ls","cat","head","tail","wc","stat","id","uname","whoami","date","getprop").contains(parts[0]))return false;
        // No quotes, substitutions, redirections, pipelines, escapes or newlines.
        return cmd.matches("[a-zA-Z0-9_./ =,:+@%\\-]+")&&!cmd.contains("..");
    }
}
