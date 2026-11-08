package dev.wagent;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
public class FileRulesTest {
    @Test public void relativeFilePathsCannotEscapeGrantedTree()throws Exception{
        for(String path:new String[]{"../private","a/../../private","/sdcard","a\\b","a//b","a/./b","a\u0000b"}){
            try{FilesStore.cleanPath(path);fail(path);}catch(IOException expected){}
        }
        assertEquals("folder/report.md",FilesStore.cleanPath("folder/report.md"));assertEquals("",FilesStore.cleanPath(""));
    }
}
