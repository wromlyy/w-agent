package dev.wagent;

import org.json.*;
import java.util.*;
import java.util.regex.Pattern;

/** Explicit local-task trust; this is app-UID execution, not an OS sandbox. */
public final class LocalTrust {
    private LocalTrust(){}
    public static boolean files(JSONObject settings,String folder,String workspace){return settings.optBoolean("autoLocal",true)&&!workspace.isEmpty()&&workspace.equals(folder);}
    public static boolean shell(JSONObject settings,JSONObject args){
        if(!settings.optBoolean("autoLocal",true)||!"linux".equals(args.optString("runtime","linux")))return false;
        String cmd=args.optString("cmd");if(ShellPolicy.blocked(cmd))return false;
        // Deletions, privilege changes and outbound publication still need separate approval.
        return !Pattern.compile("(?i)(^|[\\s;|&])(rm|sudo|su|ssh|scp|sftp)([\\s;|&]|$)|git\\s+push|curl[\\s\\S]*\\|[\\s]*(sh|bash)|wget[\\s\\S]*\\|[\\s]*(sh|bash)").matcher(cmd).find();
    }
    public static JSONObject model(JSONObject profile,JSONObject role)throws Exception{
        JSONObject result=new JSONObject(profile.toString());
        if(!result.has("effort")&&!"auto".equals(role.optString("effort","auto")))result.put("effort",role.getString("effort"));
        return result;
    }
}
