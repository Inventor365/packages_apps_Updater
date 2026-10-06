/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ServiceSpecificException;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.lineageos.updater.UpdaterApplication;
import org.lineageos.updater.data.Update;
import org.lineageos.updater.data.UpdateStatus;
import org.lineageos.updater.data.UserPreferencesRepository;
import org.lineageos.updater.download.SingleRangeHttpFetcher;
import org.lineageos.updater.misc.Constants;
import org.lineageos.updater.util.InstallFailure;
import org.lineageos.updater.util.ZipEntryLocator;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

class ABUpdateInstaller {

    private static final String TAG = "ABUpdateInstaller";

    private static final String PREF_INSTALLING_AB_ID = "installing_ab_id";
    private static final String PREF_INSTALLING_SUSPENDED_AB_ID = "installing_suspended_ab_id";

    // Not in UpdateStatusConstants: update_engine is finishing the merge of the previous update
    // before it starts on this one
    private static final int STATUS_CLEANUP_PREVIOUS_UPDATE = 11;

    private static final byte[] PAYLOAD_MAGIC = {'C', 'r', 'A', 'U'};

    private static ABUpdateInstaller sInstance = null;

    private final UpdaterController mUpdaterController;
    private final UserPreferencesRepository mUserPreferencesRepository;
    private final Context mContext;
    private volatile String mDownloadId;

    private final UpdateEngine mUpdateEngine;
    private boolean mBound;

    private boolean mFinalizing;
    private int mProgress;
    private int mLastEngineStatus = -1;

    // From applyPayload() until update_engine reports on that install. On bind, update_engine
    // sends its current status (IDLE), and that report can arrive after the install has
    // started: it must not be read as "nothing is being installed".
    private volatile boolean mStarting;

    /** Name of an UpdateEngine int constant, for logs; falls back to the raw value. */
    private static String constantName(Class<?> constants, int value) {
        for (Field field : constants.getFields()) {
            try {
                if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class
                        && field.getInt(null) == value) {
                    return field.getName() + " (" + value + ")";
                }
            } catch (IllegalAccessException ignored) {
            }
        }
        return String.valueOf(value);
    }

    private final UpdateEngineCallback mUpdateEngineCallback = new UpdateEngineCallback() {

        @Override
        public void onStatusUpdate(int status, float percent) {
            if (status != mLastEngineStatus) {
                Log.i(TAG, "update_engine status " + constantName(
                        UpdateEngine.UpdateStatusConstants.class, status) + " for " + mDownloadId);
                mLastEngineStatus = status;
            }
            if (mStarting) {
                if (status == UpdateEngine.UpdateStatusConstants.IDLE) {
                    return;
                }
                mStarting = false;
            }

            switch (status) {
                case UpdateEngine.UpdateStatusConstants.UPDATE_AVAILABLE:
                case UpdateEngine.UpdateStatusConstants.DOWNLOADING:
                case UpdateEngine.UpdateStatusConstants.VERIFYING:
                case UpdateEngine.UpdateStatusConstants.FINALIZING:
                case STATUS_CLEANUP_PREVIOUS_UPDATE: {
                    Update update = getUpdate();
                    if (update == null) {
                        // Keep following the install; there is just nothing to show it on
                        return;
                    }
                    if (status == UpdateEngine.UpdateStatusConstants.DOWNLOADING ||
                            status == UpdateEngine.UpdateStatusConstants.FINALIZING) {
                        mProgress = Math.round(percent * 100);
                    }
                    mFinalizing = status == UpdateEngine.UpdateStatusConstants.FINALIZING;
                    // update_engine keeps reporting the last status while suspended
                    UpdateStatus newStatus = isInstallingUpdateSuspended(mContext)
                            ? UpdateStatus.INSTALLATION_SUSPENDED : UpdateStatus.INSTALLING;
                    boolean changed = update.getStatus() != newStatus;
                    mUpdaterController.setUpdate(mDownloadId, update.toBuilder()
                            .setStatus(newStatus)
                            .setInstallProgress(mProgress)
                            .setFinalizing(mFinalizing)
                            .setInstallFailure(null)
                            .build());
                    if (changed) {
                        mUpdaterController.notifyUpdateChange(mDownloadId);
                    }
                    mUpdaterController.notifyInstallProgress(mDownloadId);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.UPDATED_NEED_REBOOT: {
                    installationDone(true);
                    setStatus(UpdateStatus.UPDATED_NEED_REBOOT, null);
                }
                break;

                case UpdateEngine.UpdateStatusConstants.IDLE: {
                    // update_engine isn't installing anything, so neither are we. If we thought
                    // otherwise, the result was lost (e.g. this process wasn't running).
                    installationDone(false);
                    Update update = getUpdate();
                    if (update != null && (update.getStatus() == UpdateStatus.INSTALLING ||
                            update.getStatus() == UpdateStatus.INSTALLATION_SUSPENDED)) {
                        setStatus(UpdateStatus.INSTALLATION_FAILED, InstallFailure.GENERIC);
                    }
                }
                break;
            }
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            Log.i(TAG, "update_engine finished " + mDownloadId + ": " + constantName(
                    UpdateEngine.ErrorCodeConstants.class, errorCode));
            mStarting = false;
            if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
                // UPDATED_NEED_REBOOT follows as a status update
                return;
            }
            installationDone(false);
            if (InstallFailure.isUserCanceled(errorCode)) {
                setStatus(UpdateStatus.INSTALLATION_CANCELLED, null);
            } else {
                setStatus(UpdateStatus.INSTALLATION_FAILED,
                        InstallFailure.fromErrorCode(errorCode));
            }
        }
    };

    static synchronized boolean isInstallingUpdate(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null) != null ||
                pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null) != null;
    }

    static synchronized boolean isInstallingUpdate(Context context, String downloadId) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return downloadId.equals(pref.getString(ABUpdateInstaller.PREF_INSTALLING_AB_ID, null)) ||
                TextUtils.equals(pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null), downloadId);
    }

    static synchronized boolean isInstallingUpdateSuspended(Context context) {
        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(context);
        return pref.getString(ABUpdateInstaller.PREF_INSTALLING_SUSPENDED_AB_ID, null) != null;
    }

    static synchronized boolean isWaitingForReboot(Context context, String downloadId) {
        String waitingId = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(Constants.PREF_NEEDS_REBOOT_ID, null);
        return TextUtils.equals(waitingId, downloadId);
    }

    private boolean shouldEnablePerformanceMode(boolean userPreferenceEnabled) {
        return ((UpdaterApplication) mContext).getBatteryMonitor()
                .getCurrentBatteryState().isAcCharging()
                || userPreferenceEnabled;
    }

    private void applyPerformanceMode(boolean userPreferenceEnabled) {
        try {
            mUpdateEngine.setPerformanceMode(shouldEnablePerformanceMode(userPreferenceEnabled));
        } catch (Throwable e) {
            Log.w(TAG, "Could not set performance mode", e);
        }
    }

    private ABUpdateInstaller(Context context, UpdaterController updaterController,
            UserPreferencesRepository userPreferencesRepository) {
        mUpdaterController = updaterController;
        mUserPreferencesRepository = userPreferencesRepository;
        mContext = context.getApplicationContext();
        mUpdateEngine = new UpdateEngine();
    }

    static synchronized ABUpdateInstaller getInstance(Context context,
            UpdaterController updaterController,
            UserPreferencesRepository userPreferencesRepository) {
        if (sInstance == null) {
            sInstance = new ABUpdateInstaller(context, updaterController,
                    userPreferencesRepository);
        }
        return sInstance;
    }

    private Update getUpdate() {
        String downloadId = mDownloadId;
        return downloadId != null ? mUpdaterController.getUpdate(downloadId) : null;
    }

    private void setStatus(UpdateStatus status, InstallFailure failure) {
        Update update = getUpdate();
        if (update == null) {
            return;
        }
        mUpdaterController.setUpdate(mDownloadId, update.toBuilder()
                .setStatus(status)
                .setInstallProgress(0)
                .setFinalizing(false)
                .setInstallFailure(failure)
                .build());
        mUpdaterController.notifyUpdateChange(mDownloadId);
    }

    private void fail(InstallFailure failure, String detail, Throwable cause) {
        Log.e(TAG, "Can't install " + mDownloadId + ": " + failure + " (" + detail + ")", cause);
        setStatus(UpdateStatus.INSTALLATION_FAILED, failure);
    }

    public void install(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        mDownloadId = downloadId;
        Update update = mUpdaterController.getUpdate(downloadId);
        File file = update != null ? update.getFile() : null;
        if (file == null || !file.isFile()) {
            fail(InstallFailure.PREPARE, "no package at " + file, null);
            return;
        }

        long offset;
        String[] headerKeyValuePairs;
        try {
            ZipEntryLocator.StoredEntry payload =
                    ZipEntryLocator.locateStored(file, Constants.AB_PAYLOAD_BIN_PATH);
            offset = payload.getDataOffset();
            checkPayloadMagic(file, offset);
            headerKeyValuePairs = readPayloadProperties(file);
        } catch (IOException e) {
            fail(InstallFailure.PREPARE, "could not prepare " + file, e);
            return;
        }

        String zipFileUri = "file://" + file.getAbsolutePath();
        Log.i(TAG, "Applying " + zipFileUri + " (" + file.length() + " bytes), payload offset "
                + offset);
        applyUpdate(zipFileUri, offset, 0, headerKeyValuePairs);
    }

    private static void checkPayloadMagic(File file, long offset) throws IOException {
        byte[] magic = new byte[PAYLOAD_MAGIC.length];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(magic);
        }
        if (!Arrays.equals(magic, PAYLOAD_MAGIC)) {
            throw new IOException("No payload at offset " + offset);
        }
    }

    private static String[] readPayloadProperties(File file) throws IOException {
        List<String> lines = new ArrayList<>();
        try (ZipFile zipFile = new ZipFile(file)) {
            ZipEntry entry = zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH);
            if (entry == null) {
                throw new IOException("No " + Constants.AB_PAYLOAD_PROPERTIES_PATH);
            }
            try (InputStream is = zipFile.getInputStream(entry);
                 BufferedReader br = new BufferedReader(
                         new InputStreamReader(is, StandardCharsets.UTF_8))) {
                for (String line; (line = br.readLine()) != null; ) {
                    // update_engine rejects headers that aren't key=value, blank lines included
                    line = line.trim();
                    if (!line.isEmpty()) {
                        lines.add(line);
                    }
                }
            }
        }
        if (lines.isEmpty()) {
            throw new IOException(Constants.AB_PAYLOAD_PROPERTIES_PATH + " is empty");
        }
        return lines.toArray(new String[0]);
    }

    public void installStreaming(String downloadId) {
        if (isInstallingUpdate(mContext)) {
            Log.e(TAG, "Already installing an update");
            return;
        }

        mDownloadId = downloadId;

        Update update = mUpdaterController.getUpdate(mDownloadId);
        if (update == null || update.getDownloadUrl() == null) {
            fail(InstallFailure.PREPARE, "nothing to stream", null);
            return;
        }
        String downloadUrl = update.getDownloadUrl();

        new Thread(() -> {
            try {
                String[] headerKeyValuePairs = fetchPayloadProperties(downloadUrl,
                        update.getPayloadPropertiesOffset(),
                        update.getPayloadPropertiesSize());
                applyUpdate(downloadUrl, update.getPayloadOffset(),
                        update.getPayloadSize(), headerKeyValuePairs);
            } catch (IOException | RuntimeException e) {
                fail(InstallFailure.PREPARE, "could not prepare streaming update", e);
            }
        }, "UpdaterStreamingInstall").start();
    }

    private String[] fetchPayloadProperties(String downloadUrl, long offset, long size)
            throws IOException {
        SingleRangeHttpFetcher fetcher = new SingleRangeHttpFetcher(downloadUrl);
        byte[] data = fetcher.download(offset, size);
        return new String(data, StandardCharsets.UTF_8).split("\n");
    }

    private void applyUpdate(String url, long offset, long size,
            String[] headerKeyValuePairs) {
        if (!mBound) {
            mBound = mUpdateEngine.bind(mUpdateEngineCallback);
            if (!mBound) {
                fail(InstallFailure.GENERIC, "could not bind to update_engine", null);
                return;
            }
        }

        applyPerformanceMode(mUserPreferencesRepository.getAbPerfModeBlocking());

        // Recorded before update_engine starts, so the install is picked up again if this
        // process dies right after
        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_AB_ID, mDownloadId)
                .remove(PREF_INSTALLING_SUSPENDED_AB_ID)
                .commit();
        mStarting = true;
        mProgress = 0;
        mFinalizing = false;
        setStatus(UpdateStatus.INSTALLING, null);

        try {
            mUpdateEngine.applyPayload(url, offset, size, headerKeyValuePairs);
        } catch (ServiceSpecificException e) {
            mStarting = false;
            Log.e(TAG, "applyPayload rejected " + mDownloadId + ": " + constantName(
                    UpdateEngine.ErrorCodeConstants.class, e.errorCode), e);
            if (e.errorCode == InstallFailure.ERROR_UPDATE_ALREADY_INSTALLED) {
                installationDone(true);
                setStatus(UpdateStatus.UPDATED_NEED_REBOOT, null);
                return;
            }
            installationDone(false);
            setStatus(UpdateStatus.INSTALLATION_FAILED, InstallFailure.fromErrorCode(e.errorCode));
        } catch (RuntimeException e) {
            // e.g. update_engine went away
            mStarting = false;
            installationDone(false);
            fail(InstallFailure.GENERIC, "applyPayload failed", e);
        }
    }

    public void reconnect() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "reconnect: Not installing any update");
            return;
        }

        if (mBound) {
            return;
        }

        SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(mContext);
        mDownloadId = pref.getString(PREF_INSTALLING_AB_ID,
                pref.getString(Constants.PREF_NEEDS_REBOOT_ID, null));

        // We will get a status notification as soon as we are connected
        mBound = mUpdateEngine.bind(mUpdateEngineCallback);
        if (!mBound) {
            Log.e(TAG, "Could not bind");
            return;
        }

        applyPerformanceMode(mUserPreferencesRepository.getAbPerfModeBlocking());
    }

    private void installationDone(boolean needsReboot) {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(mContext)
                .edit()
                .remove(PREF_INSTALLING_AB_ID)
                .remove(PREF_INSTALLING_SUSPENDED_AB_ID);
        if (!needsReboot) {
            editor.remove(Constants.PREF_NEEDS_REBOOT_ID);
        } else if (mDownloadId != null) {
            editor.putString(Constants.PREF_NEEDS_REBOOT_ID, mDownloadId);
        }
        editor.commit();
    }

    public void cancel() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "cancel: Not installing any update");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        try {
            mUpdateEngine.cancel();
        } catch (ServiceSpecificException e) {
            // Nothing left to cancel, e.g. it just finished
            Log.w(TAG, "update_engine refused to cancel", e);
        }
        mStarting = false;
        installationDone(false);
        setStatus(UpdateStatus.INSTALLATION_CANCELLED, null);
    }

    public void suspend() {
        if (!isInstallingUpdate(mContext)) {
            Log.e(TAG, "suspend: Not installing any update");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        try {
            mUpdateEngine.suspend();
        } catch (ServiceSpecificException e) {
            Log.w(TAG, "update_engine refused to suspend", e);
            return;
        }

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .putString(PREF_INSTALLING_SUSPENDED_AB_ID, mDownloadId)
                .commit();

        Update update = getUpdate();
        if (update != null) {
            mUpdaterController.setUpdate(mDownloadId, update.toBuilder()
                    .setStatus(UpdateStatus.INSTALLATION_SUSPENDED)
                    .build());
            mUpdaterController.notifyUpdateChange(mDownloadId);
        }
    }

    public void resume() {
        if (!isInstallingUpdateSuspended(mContext)) {
            Log.e(TAG, "resume: No update is suspended");
            return;
        }

        if (!mBound) {
            Log.e(TAG, "Not connected to update engine");
            return;
        }

        try {
            mUpdateEngine.resume();
        } catch (ServiceSpecificException e) {
            // Not suspended any more (e.g. update_engine restarted); its status updates tell
            // what it is doing
            Log.w(TAG, "update_engine refused to resume", e);
        }

        PreferenceManager.getDefaultSharedPreferences(mContext).edit()
                .remove(PREF_INSTALLING_SUSPENDED_AB_ID)
                .commit();

        Update update = getUpdate();
        if (update != null) {
            mUpdaterController.setUpdate(mDownloadId, update.toBuilder()
                    .setStatus(UpdateStatus.INSTALLING)
                    .setInstallProgress(mProgress)
                    .setFinalizing(mFinalizing)
                    .build());
            mUpdaterController.notifyUpdateChange(mDownloadId);
            mUpdaterController.notifyInstallProgress(mDownloadId);
        }
    }
}
