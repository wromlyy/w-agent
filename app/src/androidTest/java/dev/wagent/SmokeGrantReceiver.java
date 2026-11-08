package dev.wagent;

import android.app.Activity;
import android.content.*;
import android.provider.DocumentsContract;

/** The test provider's own UID grants the same tree access a picker normally grants. */
public final class SmokeGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        context.grantUriPermission("dev.wagent",DocumentsContract.buildTreeDocumentUri("dev.wagent.test.documents","root"),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        setResultCode(Activity.RESULT_OK);
    }
}
