/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import org.lineageos.updater.controller.UpdaterController;
import org.lineageos.updater.controller.UpdaterService;
import org.lineageos.updater.data.Update;
import org.lineageos.updater.data.UpdateStatus;
import org.lineageos.updater.util.PackageVerifier;
import org.lineageos.updater.util.StringUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class UpdatesActivity extends UpdatesScaffoldActivity implements UpdateImporter.Callbacks {

    private UpdaterService mUpdaterService;
    private BroadcastReceiver mBroadcastReceiver;

    private UpdatesViewModel mViewModel;

    private Update mToBeExported = null;
    private final ActivityResultLauncher<Intent> mExportUpdate = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK) {
                    Intent intent = result.getData();
                    if (intent != null) {
                        Uri uri = intent.getData();
                        exportUpdate(uri);
                    }
                }
            });

    private static final String STATE_AWAITING_IMPORT_RESULT = "awaiting_import_result";

    private UpdateImporter mUpdateImporter;
    private AlertDialog importDialog;
    // Set while this screen started a local import whose result hasn't been shown yet
    private boolean mAwaitingImportResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupCompose();

        if (savedInstanceState != null) {
            mAwaitingImportResult =
                    savedInstanceState.getBoolean(STATE_AWAITING_IMPORT_RESULT, false);
        }

        mUpdateImporter = new UpdateImporter(this, this);

        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                notifyControllerStateChanged();
                if (UpdaterController.ACTION_UPDATE_STATUS.equals(intent.getAction())) {
                    String downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID);
                    handleDownloadStatusChange(downloadId);
                }
            }
        };

        mViewModel = new ViewModelProvider(this).get(UpdatesViewModel.class);

        mViewModel.getUiStateLive().observe(this, state -> {
            if (mUpdaterService != null) {
                syncControllerUpdates(state.getUpdates());
            }
        });

    }

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(this, UpdaterService.class);
        startService(intent);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_STATUS);
        intentFilter.addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_INSTALL_PROGRESS);
        intentFilter.addAction(UpdaterController.ACTION_UPDATE_REMOVED);
        registerReceiver(mBroadcastReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The import runs in the controller, independent of this activity; pick up its state
        refreshImportState();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_AWAITING_IMPORT_RESULT, mAwaitingImportResult);
    }

    @Override
    public void onStop() {
        dismissImportDialog();
        unregisterReceiver(mBroadcastReceiver);
        if (mUpdaterService != null) {
            unbindService(mConnection);
        }
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (!mUpdateImporter.onResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onRefreshClick() {
        mViewModel.fetchUpdates();
    }

    @Override
    public void onLocalUpdateClick() {
        mUpdateImporter.openImportPicker();
    }

    @Override
    public void onImportStarted() {
        mAwaitingImportResult = true;
        refreshImportState();
    }

    @Override
    public void onImportRejected() {
        showToast(R.string.local_update_import_busy, Toast.LENGTH_LONG);
    }

    /**
     * Shows the progress of a local import while the controller copies and verifies the
     * package, then its result once, if this screen started it.
     */
    private void refreshImportState() {
        UpdaterController controller = UpdaterController.getInstance(this);
        if (controller.isImportingLocalUpdate()) {
            showImportDialog(R.string.local_update_import_progress);
            return;
        }
        if (controller.isVerifyingUpdate(Update.LOCAL_ID)) {
            showImportDialog(R.string.list_verifying_update);
            return;
        }
        dismissImportDialog();
        if (!mAwaitingImportResult) {
            return;
        }
        mAwaitingImportResult = false;

        Update update = controller.getUpdate(Update.LOCAL_ID);
        if (update != null && update.getStatus() == UpdateStatus.VERIFIED) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.local_update_import_success_title)
                    .setMessage(getString(
                            R.string.local_update_import_success_message,
                            StringUtil.formatBuildDate(this, update.getTimestamp())))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } else {
            showVerificationFailure(R.string.local_update_import_failure,
                    update != null ? update.getVerificationFailure() : null);
        }
    }

    private void showImportDialog(int messageRes) {
        if (importDialog == null) {
            importDialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.local_update_import)
                    .setView(R.layout.progress_dialog)
                    .setCancelable(false)
                    .create();
        }
        if (!importDialog.isShowing()) {
            importDialog.show();
        }
        TextView text = importDialog.findViewById(R.id.progress_text);
        if (text != null) {
            text.setText(messageRes);
        }
    }

    private void dismissImportDialog() {
        if (importDialog != null) {
            importDialog.dismiss();
            importDialog = null;
        }
    }

    private void showVerificationFailure(int titleRes, PackageVerifier.Failure failure) {
        new AlertDialog.Builder(this)
                .setTitle(titleRes)
                .setMessage(failure != null ? failure.getMessageRes()
                        : R.string.snack_download_verification_failed)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className,
                IBinder service) {
            UpdaterService.LocalBinder binder = (UpdaterService.LocalBinder) service;
            mUpdaterService = binder.getService();
            setUpdaterController(mUpdaterService.getUpdaterController());
            syncControllerUpdates(
                    Objects.requireNonNull(mViewModel.getUiState().getValue()).getUpdates());
            refreshImportState();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            setUpdaterController(null);
            mUpdaterService = null;
        }
    };

    private void syncControllerUpdates(List<Update> updates) {
        UpdaterController controller = mUpdaterService.getUpdaterController();
        List<String> onlineUpdateIds = new ArrayList<>();
        for (Update update : updates) {
            boolean availableOnline = !Update.LOCAL_ID.equals(update.getDownloadId());
            controller.addUpdate(update, availableOnline);
            if (availableOnline) {
                onlineUpdateIds.add(update.getDownloadId());
            }
        }

        controller.setUpdatesAvailableOnline(onlineUpdateIds, true);
    }

    private void handleDownloadStatusChange(String downloadId) {
        if (Update.LOCAL_ID.equals(downloadId)) {
            refreshImportState();
            return;
        }

        Update update = UpdaterController.getInstance(this).getUpdate(downloadId);
        if (update == null) {
            return;
        }
        switch (update.getStatus()) {
            case PAUSED_ERROR:
                showToast(update.getVerificationFailure() != null
                        ? update.getVerificationFailure().getMessageRes()
                        : R.string.snack_download_failed, Toast.LENGTH_LONG);
                break;
            case VERIFICATION_FAILED:
                // The package was discarded; say why, so it isn't mistaken for a lost download
                showVerificationFailure(R.string.snack_download_verification_failed,
                        update.getVerificationFailure());
                break;
            case VERIFIED:
                showToast(R.string.snack_download_verified, Toast.LENGTH_LONG);
                break;
        }
    }

    @Override
    public void exportUpdate(Update update) {
        mToBeExported = update;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, update.getName());

        mExportUpdate.launch(intent);
    }

    private void exportUpdate(Uri uri) {
        Intent intent = new Intent(this, ExportUpdateService.class);
        intent.setAction(ExportUpdateService.ACTION_START_EXPORTING);
        intent.putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported.getFile());
        intent.putExtra(ExportUpdateService.EXTRA_DEST_URI, uri);
        startService(intent);
    }

    public void showToast(int stringId, int duration) {
        Toast.makeText(this, stringId, duration).show();
    }
}
