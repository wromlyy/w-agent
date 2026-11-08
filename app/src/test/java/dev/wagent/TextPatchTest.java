package dev.wagent;
import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class TextPatchTest {
    @Test public void changesOnlyMatchingLinesAndPreservesRest()throws Exception{assertEquals("one\nnew\nthree\n",TextPatch.apply("one\ntwo\nthree\n","--- a\n+++ b\n@@ -1,2 +1,2 @@\n one\n-two\n+new\n"));}
    @Test public void rejectsStaleAndMalformedDiff(){assertThrows(IOException.class,()->TextPatch.apply("changed\n","@@ -1 +1 @@\n-original\n+new\n"));assertThrows(IOException.class,()->TextPatch.apply("one\n","@@ -1,2 +1 @@\n-one\n+new\n"));}
    @Test public void insertionAndMultipleHunks()throws Exception{assertEquals("a\nx\nb\ny\n",TextPatch.apply("a\nb\n","@@ -1,0 +2 @@\n+x\n@@ -2,0 +4 @@\n+y\n"));}
}
