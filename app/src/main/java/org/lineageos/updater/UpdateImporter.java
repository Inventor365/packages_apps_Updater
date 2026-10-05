/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: 2020-2022 SHIFT GmbH
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.lineageos.updater.controller.UpdaterController;

/**
 * Lets the user pick a local update package and hands it to {@link UpdaterController}, which
 * copies and verifies it independently of the activity's lifecycle and reports progress
 * through its usual status broadcasts.
 */
public class UpdateImporter {
    private static final int REQUEST_PICK = 9061;
    private static final String TAG = "UpdateImporter";
    private static final String MIME_ZIP = "application/zip";

    private final Activity activity;
    private final Callbacks callbacks;

    public UpdateImporter(Activity activity, Callbacks callbacks) {
        this.activity = activity;
        this.callbacks = callbacks;
    }

    public void openImportPicker() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIME_ZIP);
        activity.startActivityForResult(intent, REQUEST_PICK);
    }

    public boolean onResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PICK) {
            return false;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return true;
        }

        final Uri uri = data.getData();
        if (UpdaterController.getInstance(activity).importLocalUpdate(uri)) {
            callbacks.onImportStarted();
        } else {
            Log.w(TAG, "Import of " + uri + " not started");
            callbacks.onImportRejected();
        }
        return true;
    }

    public interface Callbacks {
        void onImportStarted();

        void onImportRejected();
    }
}
