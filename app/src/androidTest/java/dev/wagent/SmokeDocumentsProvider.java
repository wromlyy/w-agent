package dev.wagent;
import android.database.*;
import android.os.*;
import android.provider.*;
import java.io.*;

/** Disposable CI-only document provider. Never packaged in W Agent's release APK. */
public class SmokeDocumentsProvider extends DocumentsProvider {
    private File root(){File f=new File(getContext().getFilesDir(),"project-smoke");f.mkdirs();return f;}
    private File file(String id)throws FileNotFoundException{try{return id.equals("root")?root():new File(root(),FilesStore.cleanPath(id));}catch(IOException e){throw new FileNotFoundException();}}
    private String id(File f){return f.equals(root())?"root":f.getAbsolutePath().substring(root().getAbsolutePath().length()+1);}
    @Override public boolean onCreate(){return true;}
    @Override public Cursor queryRoots(String[] projection){MatrixCursor c=new MatrixCursor(new String[]{DocumentsContract.Root.COLUMN_ROOT_ID,DocumentsContract.Root.COLUMN_DOCUMENT_ID,DocumentsContract.Root.COLUMN_TITLE});c.addRow(new Object[]{"smoke","root","Smoke project"});return c;}
    private void row(MatrixCursor cursor,File f)throws FileNotFoundException{if(!f.exists())throw new FileNotFoundException();Object[] values=new Object[cursor.getColumnCount()];String[] columns=cursor.getColumnNames();for(int i=0;i<columns.length;i++)values[i]=switch(columns[i]){case DocumentsContract.Document.COLUMN_DOCUMENT_ID->id(f);case DocumentsContract.Document.COLUMN_DISPLAY_NAME->f.getName();case DocumentsContract.Document.COLUMN_MIME_TYPE->f.isDirectory()?DocumentsContract.Document.MIME_TYPE_DIR:"text/plain";case DocumentsContract.Document.COLUMN_SIZE->f.length();case DocumentsContract.Document.COLUMN_LAST_MODIFIED->f.lastModified();case DocumentsContract.Document.COLUMN_FLAGS->DocumentsContract.Document.FLAG_SUPPORTS_WRITE|DocumentsContract.Document.FLAG_SUPPORTS_DELETE|(f.isDirectory()?DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE:0);default->null;};cursor.addRow(values);}
    private String[] columns(String[] p){return p==null?new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_FLAGS}:p;}
    @Override public Cursor queryDocument(String documentId,String[] projection)throws FileNotFoundException{MatrixCursor c=new MatrixCursor(columns(projection));row(c,file(documentId));return c;}
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order)throws FileNotFoundException{MatrixCursor c=new MatrixCursor(columns(projection));File[] children=file(parent).listFiles();if(children!=null)for(File f:children)row(c,f);return c;}
    @Override public ParcelFileDescriptor openDocument(String documentId,String mode,CancellationSignal signal)throws FileNotFoundException{return ParcelFileDescriptor.open(file(documentId),ParcelFileDescriptor.parseMode(mode));}
    @Override public String createDocument(String parent,String mime,String name)throws FileNotFoundException{File f=new File(file(parent),name);try{if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){if(!f.mkdir())throw new IOException();}else if(!f.createNewFile())throw new IOException();return id(f);}catch(IOException e){throw new FileNotFoundException();}}
    @Override public void deleteDocument(String id)throws FileNotFoundException{if(!file(id).delete())throw new FileNotFoundException();}
}
