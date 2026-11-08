package dev.wagent;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Strict single-file unified diff. Every context/deletion line must match. */
public final class TextPatch {
    public static String apply(String original,String diff)throws IOException{
        if(diff.length()>400000)throw new IOException("Diff слишком большой");
        List<String> source=new ArrayList<>(Arrays.asList(original.split("\n",-1))),out=new ArrayList<>();
        String[] lines=diff.split("\n",-1);int cursor=0,i=0,hunks=0;
        Pattern header=Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
        while(i<lines.length&&(lines[i].startsWith("--- ")||lines[i].startsWith("+++ ")||lines[i].isEmpty()))i++;
        while(i<lines.length){
            if(i==lines.length-1&&lines[i].isEmpty())break;Matcher m=header.matcher(lines[i++]);if(!m.matches())throw new IOException("Ожидался заголовок @@ unified diff");hunks++;
            int start=Integer.parseInt(m.group(1)),oldCount=m.group(2)==null?1:Integer.parseInt(m.group(2)),newCount=m.group(4)==null?1:Integer.parseInt(m.group(4));
            int target=oldCount==0?start:start-1;if(target<cursor||target>source.size())throw new IOException("Неверная позиция diff");
            while(cursor<target)out.add(source.get(cursor++));int removed=0,added=0;
            while(i<lines.length&&!lines[i].startsWith("@@ ")){
                String line=lines[i];if(i==lines.length-1&&line.isEmpty()){i++;break;}i++;
                if(line.startsWith("\\ No newline"))continue;if(line.isEmpty())throw new IOException("Строке diff нужен префикс пробел, + или -");
                char kind=line.charAt(0);String text=line.substring(1);if(kind==' '||kind=='-'){if(cursor>=source.size()||!source.get(cursor).equals(text))throw new IOException("Контекст diff не совпадает со строкой "+(cursor+1)+". Прочитайте актуальный файл.");cursor++;removed++;}
                if(kind==' '||kind=='+'){out.add(text);added++;}if(kind!=' '&&kind!='-'&&kind!='+')throw new IOException("Недопустимая строка diff");
            }
            if(removed!=oldCount||added!=newCount)throw new IOException("Количество строк в diff не совпадает с заголовком");
        }
        if(hunks==0)throw new IOException("Diff не содержит изменений");while(cursor<source.size())out.add(source.get(cursor++));return String.join("\n",out);
    }
    public static String preview(String before,String after){
        String[] a=before.split("\n",-1),b=after.split("\n",-1);int prefix=0;while(prefix<Math.min(a.length,b.length)&&a[prefix].equals(b[prefix]))prefix++;
        int suffix=0;while(suffix<Math.min(a.length,b.length)-prefix&&a[a.length-1-suffix].equals(b[b.length-1-suffix]))suffix++;
        StringBuilder s=new StringBuilder("@@ строка "+(prefix+1)+" @@\n");for(int i=prefix;i<a.length-suffix;i++)s.append('-').append(a[i]).append('\n');for(int i=prefix;i<b.length-suffix;i++)s.append('+').append(b[i]).append('\n');return s.toString();
    }
}
