package dev.wagent;
import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;

/** Read-only grants for files explicitly shared through the Android chooser. */
public class OutputProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private FilesStore store(){return new FilesStore((WApp)getContext().getApplicationContext());}
    private String id(Uri uri)throws FileNotFoundException{if(uri.getPathSegments().size()!=2||!"output".equals(uri.getPathSegments().get(0)))throw new FileNotFoundException();return uri.getLastPathSegment();}
    @Override public String getType(Uri uri){try{return store().outputInfo(id(uri)).getString("mime");}catch(Exception e){return "application/octet-stream";}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{JSONObject file=store().outputInfo(id(uri));String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor result=new MatrixCursor(columns);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?file.getString("name"):OpenableColumns.SIZE.equals(columns[i])?file.getLong("size"):null;result.addRow(row);return result;}catch(Exception e){return null;}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{if(!"r".equals(mode))throw new FileNotFoundException("Только чтение");try{return ParcelFileDescriptor.open(store().outputFile(id(uri)),ParcelFileDescriptor.MODE_READ_ONLY);}catch(Exception e){throw new FileNotFoundException("Файл недоступен");}}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
