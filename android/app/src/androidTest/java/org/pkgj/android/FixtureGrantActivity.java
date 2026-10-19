package org.pkgj.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Test APK only: grants access to synthetic documents from their owning UID. */
public final class FixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        grantUriPermission("org.pkgj.android.catalog",
            DocumentsContract.buildTreeDocumentUri("org.pkgj.android.catalog.test.documents", "root"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        finish();
    }
}
