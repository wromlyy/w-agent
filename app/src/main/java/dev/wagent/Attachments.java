package dev.wagent;
import android.graphics.*;
import org.json.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Attachments {
    static JSONObject create(String name,String mime,byte[] bytes,boolean skill)throws Exception{
        mime=mime==null?"application/octet-stream":mime;
        JSONObject file=new JSONObject().put("id",UUID.randomUUID().toString()).put("name",name).put("mime",mime).put("size",bytes.length);
        if(skill||mime.startsWith("text/")||"application/json".equals(mime)||name.matches("(?i).+\\.(md|txt|json|csv|js|ts|tsx|jsx|kt|java|py|xml|html|css|yaml|yml|log|sh)$")){
            if(bytes.length>200000)throw new IOException("Текстовый файл: максимум 200 КБ");return file.put("text",new String(bytes,StandardCharsets.UTF_8));
        }
        if(mime.startsWith("image/")||name.matches("(?i).+\\.(jpg|jpeg|png|webp|heic|heif|gif)$")){
            Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)),(decoder,info,source)->{android.util.Size size=info.getSize();double scale=Math.min(1d,1600d/Math.max(size.getWidth(),size.getHeight()));decoder.setTargetSize(Math.max(1,(int)(size.getWidth()*scale)),Math.max(1,(int)(size.getHeight()*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
            try{ByteArrayOutputStream out=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,85,out))throw new IOException("Не удалось подготовить фото");bytes=out.toByteArray();file.put("mime","image/jpeg").put("size",bytes.length).put("width",bitmap.getWidth()).put("height",bitmap.getHeight());}finally{bitmap.recycle();}
        }else if(!"application/pdf".equals(mime))throw new IOException("Поддерживаются текст, код, изображения и PDF");
        if(bytes.length>4*1024*1024)throw new IOException("Вложение после подготовки больше 4 МБ");return file.put("data",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));
    }
}
