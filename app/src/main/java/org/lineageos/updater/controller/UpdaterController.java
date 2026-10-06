/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.controller;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.lineageos.updater.R;
import org.lineageos.updater.UpdaterApplication;
import org.lineageos.updater.data.Update;
import org.lineageos.updater.data.UpdateStatus;
import org.lineageos.updater.data.UserPreferencesRepository;
import org.lineageos.updater.data.source.local.UpdatesLocalDataSource;
import org.lineageos.updater.data.source.local.UpdatesDatabase;
import org.lineageos.updater.deviceinfo.DeviceInfoUtils;
import org.lineageos.updater.download.DownloadClient;
import org.lineageos.updater.download.PartialDownload;
import org.lineageos.updater.misc.Utils;
import org.lineageos.updater.util.OtaMetadataParser;
import org.lineageos.updater.util.PackageVerifier;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class UpdaterController {

    public static final String ACTION_DOWNLOAD_PROGRESS = "action_download_progress";
    public static final String ACTION_INSTALL_PROGRESS = "action_install_progress";
    public static final String ACTION_UPDATE_REMOVED = "action_update_removed";
    public static final String ACTION_UPDATE_STATUS = "action_update_status_change";
    public static final String EXTRA_DOWNLOAD_ID = "extra_download_id";

    private final String TAG = "UpdaterController";

    private static UpdaterController sUpdaterController;

    private static final int MAX_REPORT_INTERVAL_MS = 1000;

    private static final String LOCAL_UPDATE_FILE_NAME = "localUpdate.zip";
    private static final String PARTIAL_SUFFIX = ".part";
    // Never fill the data partition: leave this much free after a download or an import
    private static final long MIN_FREE_BYTES = 512L * 1024 * 1024;
    private static final long SPACE_CHECK_INTERVAL = 64L * 1024 * 1024;

    private final Context mContext;
    private final UpdatesLocalDataSource mUpdatesLocalDataSource;

    private final PowerManager.WakeLock mWakeLock;

    private final File mDownloadRoot;

    private int mActiveDownloads = 0;
    private final Set<String> mVerifyingUpdates = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean mImportingLocalUpdate = new AtomicBoolean(false);

    public static synchronized UpdaterController getInstance(Context context) {
        if (sUpdaterController == null) {
            UserPreferencesRepository userPreferencesRepository =
                    ((UpdaterApplication) context.getApplicationContext())
                            .getUserPreferencesRepository();
            sUpdaterController = new UpdaterController(context, userPreferencesRepository);
        }
        return sUpdaterController;
    }

    private UpdaterController(Context context, UserPreferencesRepository userPreferencesRepository) {
        mUpdatesLocalDataSource =
                new UpdatesLocalDataSource(UpdatesDatabase.getInstance(context).updateDao());
        mDownloadRoot = Utils.getDownloadPath(context);
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        mWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Updater:wakelock");
        mWakeLock.setReferenceCounted(false);
        mContext = context.getApplicationContext();

        // A local import cut short by process death leaves a partial copy behind. Remove it
        // before an import can start in this process.
        deleteQuietly(new File(mDownloadRoot, LOCAL_UPDATE_FILE_NAME + PARTIAL_SUFFIX));

        new Thread(() -> {
            Utils.cleanupDownloadsDir(context, userPreferencesRepository);
            for (Update update : mUpdatesLocalDataSource.getUpdates()) {
                addUpdate(update, false);
            }
        }).start();
    }

    private static class DownloadEntry {
        volatile Update mUpdate;
        volatile DownloadClient mDownloadClient;
        private DownloadEntry(Update update) {
            mUpdate = update;
        }
    }

    private final Map<String, DownloadEntry> mDownloads = new ConcurrentHashMap<>();

    void notifyUpdateChange(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_UPDATE_STATUS);
        intent.setPackage(mContext.getPackageName());
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mContext.sendBroadcast(intent);
    }

    void notifyUpdateDelete(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_UPDATE_REMOVED);
        intent.setPackage(mContext.getPackageName());
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mContext.sendBroadcast(intent);
    }

    void notifyDownloadProgress(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_DOWNLOAD_PROGRESS);
        intent.setPackage(mContext.getPackageName());
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mContext.sendBroadcast(intent);
    }

    void notifyInstallProgress(String downloadId) {
        Intent intent = new Intent();
        intent.setAction(ACTION_INSTALL_PROGRESS);
        intent.setPackage(mContext.getPackageName());
        intent.putExtra(EXTRA_DOWNLOAD_ID, downloadId);
        mContext.sendBroadcast(intent);
    }

    private void logTransition(String downloadId, UpdateStatus from, UpdateStatus to,
            String detail) {
        Log.i(TAG, "[" + downloadId + "] " + from + " -> " + to
                + (detail != null ? " (" + detail + ")" : ""));
    }

    private void setStatus(DownloadEntry entry, UpdateStatus status, String detail) {
        UpdateStatus previous;
        synchronized (entry) {
            previous = entry.mUpdate.getStatus();
            entry.mUpdate = entry.mUpdate.withStatus(status);
        }
        logTransition(entry.mUpdate.getDownloadId(), previous, status, detail);
    }

    private void tryReleaseWakelock() {
        if (!hasActiveDownloads() && mVerifyingUpdates.isEmpty()) {
            mWakeLock.release();
        }
    }

    private void addDownloadClient(DownloadEntry entry, DownloadClient downloadClient) {
        if (entry.mDownloadClient != null) {
            return;
        }
        entry.mDownloadClient = downloadClient;
        mActiveDownloads++;
    }

    private void removeDownloadClient(DownloadEntry entry) {
        if (entry.mDownloadClient == null) {
            return;
        }
        entry.mDownloadClient = null;
        mActiveDownloads--;
    }

    /**
     * Download and progress callbacks of one download client. Callbacks from a client that is
     * no longer the active one for its entry (paused, cancelled or replaced by a resume) are
     * dropped so they can't overwrite the state of the current download.
     */
    private class ClientCallbacks implements DownloadClient.DownloadCallback,
            DownloadClient.ProgressListener {
        private final String mDownloadId;
        private DownloadClient mClient;
        private long mLastUpdate = 0;
        private int mProgress = 0;

        private ClientCallbacks(String downloadId) {
            mDownloadId = downloadId;
        }

        private DownloadEntry getActiveEntry() {
            DownloadEntry entry = mDownloads.get(mDownloadId);
            return entry != null && entry.mDownloadClient == mClient ? entry : null;
        }

        @Override
        public void onResponse(DownloadClient.Headers headers) {
            final DownloadEntry entry = getActiveEntry();
            if (entry == null) {
                return;
            }
            final Update newUpdate;
            synchronized (entry) {
                final Update update = entry.mUpdate;
                Update.Builder builder = update.toBuilder();
                String contentLength = headers.get("Content-Length");
                if (contentLength != null) {
                    try {
                        long size = Long.parseLong(contentLength);
                        if (update.getFileSize() < size) {
                            builder.setFileSize(size);
                        }
                    } catch (NumberFormatException e) {
                        Log.e(TAG, "Could not get content-length");
                    }
                }
                builder.setStatus(UpdateStatus.DOWNLOADING);
                newUpdate = builder.build();
                entry.mUpdate = newUpdate;
            }
            logTransition(mDownloadId, UpdateStatus.STARTING, UpdateStatus.DOWNLOADING,
                    newUpdate.getFile() + ", expected size " + newUpdate.getFileSize());
            new Thread(() -> mUpdatesLocalDataSource.addUpdate(newUpdate)).start();
            notifyUpdateChange(mDownloadId);
        }

        @Override
        public void onSuccess() {
            DownloadEntry entry = getActiveEntry();
            if (entry == null) {
                Log.d(TAG, "Ignoring completion of inactive download " + mDownloadId);
                return;
            }
            removeDownloadClient(entry);
            setStatus(entry, UpdateStatus.VERIFYING, "download complete");
            verifyUpdateAsync(mDownloadId);
            notifyUpdateChange(mDownloadId);
            tryReleaseWakelock();
        }

        @Override
        public void onFailure(boolean cancelled) {
            if (cancelled) {
                Log.d(TAG, "Download cancelled: " + mDownloadId);
                // Already notified
            } else {
                DownloadEntry entry = getActiveEntry();
                if (entry != null) {
                    removeDownloadClient(entry);
                    setStatus(entry, UpdateStatus.PAUSED_ERROR, "download failed");
                    notifyUpdateChange(mDownloadId);
                }
            }
            tryReleaseWakelock();
        }

        @Override
        public void update(long bytesRead, long contentLength, long speed, long eta) {
            DownloadEntry entry = getActiveEntry();
            if (entry == null) {
                return;
            }
            Update update = entry.mUpdate;
            if (contentLength <= 0) {
                if (update.getFileSize() <= 0) {
                    return;
                } else {
                    contentLength = update.getFileSize();
                }
            }
            if (contentLength <= 0) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            int progress = Math.round(bytesRead * 100f / contentLength);
            if (progress != mProgress || now - mLastUpdate > MAX_REPORT_INTERVAL_MS) {
                mProgress = progress;
                mLastUpdate = now;
                synchronized (entry) {
                    entry.mUpdate = entry.mUpdate.toBuilder()
                            .setProgress(progress)
                            .setEta(eta)
                            .setSpeed(speed)
                            .build();
                }
                notifyDownloadProgress(mDownloadId);
            }
        }
    }

    private DownloadClient buildDownloadClient(String downloadId, Update update)
            throws IOException {
        ClientCallbacks callbacks = new ClientCallbacks(downloadId);
        callbacks.mClient = new DownloadClient.Builder()
                .setUrl(update.getDownloadUrl())
                .setDestination(update.getFile())
                .setDownloadCallback(callbacks)
                .setProgressListener(callbacks)
                .setUseDuplicateLinks(true)
                .build();
        return callbacks.mClient;
    }

    private static boolean shouldVerifySignature(boolean isLocal) {
        // Imported packages on legacy devices are applied by recovery, which enforces its own
        // key policy. Everywhere else the installer (update_engine, or recovery for downloads)
        // trusts exactly otacerts.zip, so a package it would reject is caught here instead.
        return !isLocal || DeviceInfoUtils.isABDevice();
    }

    /**
     * Verifies the package of an update that is already on disk. This is the single
     * verification path for downloaded and imported packages alike.
     */
    @SuppressLint("WakelockTimeout")
    private void verifyUpdateAsync(final String downloadId) {
        final DownloadEntry entry = mDownloads.get(downloadId);
        if (entry == null) {
            Log.e(TAG, "Can't verify unknown update " + downloadId);
            return;
        }
        if (!mVerifyingUpdates.add(downloadId)) {
            Log.d(TAG, downloadId + " is already being verified");
            return;
        }
        // Hashing and checking the signature of a multi-GB package takes a while; don't let the
        // device suspend under it (the download wakelock is released when the download ends).
        mWakeLock.acquire();
        new Thread(() -> {
            final Update update = entry.mUpdate;
            final boolean isLocal = Update.LOCAL_ID.equals(downloadId);
            try {
                PackageVerifier.Result result = PackageVerifier.verify(update.getFile(),
                        isLocal ? 0 : update.getFileSize(), update.getExpectedSha256(),
                        shouldVerifySignature(isLocal), DeviceInfoUtils.getDeviceNames());
                if (result.isVerified()) {
                    onVerified(entry);
                } else {
                    onVerificationFailed(entry, result);
                }
            } catch (RuntimeException e) {
                // Never offer a package we failed to check, but don't destroy it either.
                Log.e(TAG, "Unexpected error verifying " + downloadId, e);
                onVerificationFailed(entry, new PackageVerifier.Result(
                        PackageVerifier.Failure.INTERRUPTED, String.valueOf(e)));
            } finally {
                mVerifyingUpdates.remove(downloadId);
                tryReleaseWakelock();
                notifyUpdateChange(downloadId);
            }
        }, "UpdaterVerify").start();
    }

    @SuppressLint("SetWorldReadable")
    private void onVerified(DownloadEntry entry) {
        Update verified;
        synchronized (entry) {
            //noinspection ResultOfMethodCallIgnored
            entry.mUpdate.getFile().setReadable(true, false);
            verified = entry.mUpdate.toBuilder()
                    .setStatus(UpdateStatus.VERIFIED)
                    .setVerificationFailure(null)
                    .setInstallFailure(null)
                    .build();
            entry.mUpdate = verified;
        }
        logTransition(verified.getDownloadId(), UpdateStatus.VERIFYING, UpdateStatus.VERIFIED,
                verified.getFile().getAbsolutePath());
        // Persist the whole row rather than just the status, so the path is always recorded
        // together with the verified state.
        mUpdatesLocalDataSource.addUpdate(verified);
    }

    private void onVerificationFailed(DownloadEntry entry, PackageVerifier.Result result) {
        final PackageVerifier.Failure failure = result.getFailure();
        final Update failed;
        synchronized (entry) {
            Update update = entry.mUpdate;
            File file = update.getFile();
            if (failure.getKeepsPackage()) {
                // Nothing is wrong with the bytes we have, there just aren't enough of them (or
                // checking them was cut short). Keep them so the download can be resumed.
                int progress = file != null && update.getFileSize() > 0
                        ? Math.round(file.length() * 100f / update.getFileSize())
                        : update.getProgress();
                failed = update.toBuilder()
                        .setStatus(UpdateStatus.PAUSED_ERROR)
                        .setProgress(progress)
                        .setVerificationFailure(failure)
                        .build();
            } else {
                deleteQuietly(file);
                failed = update.toBuilder()
                        .setFile(null)
                        .setProgress(0)
                        .setStatus(UpdateStatus.VERIFICATION_FAILED)
                        .setVerificationFailure(failure)
                        .build();
            }
            entry.mUpdate = failed;
        }
        logTransition(failed.getDownloadId(), UpdateStatus.VERIFYING, failed.getStatus(),
                failure + ": " + result.getDetail());

        if (failure.getKeepsPackage()) {
            mUpdatesLocalDataSource.addUpdate(failed);
        } else if (Update.LOCAL_ID.equals(failed.getDownloadId())) {
            mUpdatesLocalDataSource.removeUpdate(failed.getDownloadId());
        } else {
            // Keep the update listed, now without a package, so it doesn't vanish from the list
            // and come back later looking like an update that was never downloaded.
            mUpdatesLocalDataSource.addUpdate(failed);
        }
    }

    public void setUpdatesAvailableOnline(List<String> downloadIds, boolean purgeList) {
        List<String> toRemove = new ArrayList<>();
        for (DownloadEntry entry : mDownloads.values()) {
            boolean online = downloadIds.contains(entry.mUpdate.getDownloadId());
            entry.mUpdate = entry.mUpdate.withAvailableOnline(online);
            if (!online && purgeList &&
                    entry.mUpdate.getStatus().getPersistentStatus() == 0) {
                toRemove.add(entry.mUpdate.getDownloadId());
            }
        }
        for (String downloadId : toRemove) {
            Log.d(TAG, downloadId + " no longer available online, removing");
            mDownloads.remove(downloadId);
            notifyUpdateDelete(downloadId);
        }
    }

    public boolean addUpdate(Update update) {
        return addUpdate(update, true);
    }

    private void mergeUpdateInfo(DownloadEntry entry, Update updateInfo,
            boolean availableOnline) {
        synchronized (entry) {
            entry.mUpdate = entry.mUpdate.toBuilder()
                    .setAvailableOnline(availableOnline && entry.mUpdate.isAvailableOnline())
                    .setDownloadUrl(updateInfo.getDownloadUrl())
                    .setOsPatchLevel(updateInfo.getOsPatchLevel())
                    .setOsSdkLevel(updateInfo.getOsSdkLevel())
                    .setPayloadMetadataOffset(updateInfo.getPayloadMetadataOffset())
                    .setPayloadMetadataSize(updateInfo.getPayloadMetadataSize())
                    .setPayloadOffset(updateInfo.getPayloadOffset())
                    .setPayloadSize(updateInfo.getPayloadSize())
                    .setPayloadPropertiesOffset(updateInfo.getPayloadPropertiesOffset())
                    .setPayloadPropertiesSize(updateInfo.getPayloadPropertiesSize())
                    .build();
        }
    }

    public boolean addUpdate(final Update updateInfo, boolean availableOnline) {
        final String downloadId = updateInfo.getDownloadId();
        Log.d(TAG, "Adding download: " + downloadId);
        DownloadEntry existing = mDownloads.get(downloadId);
        if (existing != null) {
            Log.d(TAG, "Download (" + downloadId + ") already added");
            mergeUpdateInfo(existing, updateInfo, availableOnline);
            return false;
        }

        Update.Builder builder = updateInfo.toBuilder();
        final File file = updateInfo.getFile();
        final UpdateStatus status = updateInfo.getStatus();
        boolean needsVerification = false;
        if (status.hasVerifiedPackage() || status == UpdateStatus.PAUSED ||
                status == UpdateStatus.PAUSED_ERROR) {
            final boolean complete = file != null && file.exists();
            if (file == null || !PartialDownload.exists(file)) {
                if (!availableOnline) {
                    deleteUpdateAsync(updateInfo);
                    Log.d(TAG, downloadId + " had an invalid status and is not online");
                    return false;
                }
                builder.setStatus(UpdateStatus.UPDATE_AVAILABLE);
                builder.setProgress(0);
            } else if (status.hasVerifiedPackage() && complete) {
                // The package changed since it was verified; check it again before offering it.
                needsVerification = updateInfo.getFileSize() > 0 &&
                        file.length() != updateInfo.getFileSize();
            } else if (updateInfo.getFileSize() > 0) {
                builder.setStatus(UpdateStatus.PAUSED);
                int progress = Math.round(PartialDownload.downloadedBytes(file) * 100f /
                        updateInfo.getFileSize());
                builder.setProgress(progress);
                // A complete package still in a download state was never verified, typically
                // because the process died while verifying. Finish that instead of making the
                // user download it again.
                needsVerification = complete && file.length() >= updateInfo.getFileSize();
            }
        }
        builder.setAvailableOnline(availableOnline);
        DownloadEntry entry = new DownloadEntry(builder.build());
        existing = mDownloads.putIfAbsent(downloadId, entry);
        if (existing != null) {
            mergeUpdateInfo(existing, updateInfo, availableOnline);
            return false;
        }
        if (needsVerification) {
            setStatus(entry, UpdateStatus.VERIFYING, "restored complete package " + file);
            verifyUpdateAsync(downloadId);
            notifyUpdateChange(downloadId);
        }
        return true;
    }

    /**
     * Free space needed to download the rest of the package: what is left of it, plus what is
     * always kept free. 0 if nothing is left to download (or the size isn't known).
     */
    public long getSpaceNeededForDownload(Update update) {
        File file = update.getFile();
        long remaining = update.getFileSize() -
                (file != null ? PartialDownload.downloadedBytes(file) : 0);
        return remaining > 0 ? remaining + MIN_FREE_BYTES : 0;
    }

    /** Whether the rest of the package fits on the data partition, leaving some room. */
    public boolean hasRoomForDownload(Update update) {
        return mDownloadRoot.getUsableSpace() >= getSpaceNeededForDownload(update);
    }

    private boolean isFileUsedByOtherUpdate(File file, String downloadId) {
        for (DownloadEntry entry : mDownloads.values()) {
            Update update = entry.mUpdate;
            if (!update.getDownloadId().equals(downloadId) && file.equals(update.getFile())) {
                return true;
            }
        }
        return false;
    }

    @SuppressLint("WakelockTimeout")
    public void startDownload(String downloadId) {
        Log.d(TAG, "Starting " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId) ||
                isVerifyingUpdate(downloadId)) {
            return;
        }
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry == null) {
            Log.e(TAG, "Could not get download entry");
            return;
        }
        Update update = entry.mUpdate;
        File destination = new File(mDownloadRoot, update.getName());
        if (PartialDownload.exists(destination)) {
            if (isFileUsedByOtherUpdate(destination, downloadId)) {
                destination = Utils.appendSequentialNumber(destination);
                Log.d(TAG, "Changing name with " + destination.getName());
            } else {
                // The package, or part of it, is already here (e.g. its database row was lost,
                // or a download was interrupted). Verify or continue it rather than download it
                // all again; the download client checks it still matches the server's file.
                Log.i(TAG, "Continuing with existing package " + destination + " for "
                        + downloadId);
                entry.mUpdate = update.withFile(destination);
                resumeDownload(downloadId);
                return;
            }
        }
        if (!hasRoomForDownload(update.withFile(destination))) {
            Log.e(TAG, "Not enough free space to download " + downloadId + " ("
                    + update.getFileSize() + " bytes, " + mDownloadRoot.getUsableSpace()
                    + " usable)");
            entry.mUpdate = update.withVerificationFailure(PackageVerifier.Failure.NO_SPACE);
            notifyUpdateChange(downloadId);
            return;
        }
        pauseActiveDownloads();
        entry.mUpdate = update.withFile(destination).withVerificationFailure(null);
        Log.i(TAG, "Downloading " + downloadId + " from " + entry.mUpdate.getDownloadUrl()
                + " to " + destination + ", expected size " + update.getFileSize()
                + ", expected SHA-256 " + update.getExpectedSha256());
        DownloadClient downloadClient;
        try {
            downloadClient = buildDownloadClient(downloadId, entry.mUpdate);
        } catch (IOException exception) {
            Log.e(TAG, "Could not build download client", exception);
            setStatus(entry, UpdateStatus.PAUSED_ERROR, "could not build download client");
            notifyUpdateChange(downloadId);
            return;
        }
        addDownloadClient(entry, downloadClient);
        setStatus(entry, UpdateStatus.STARTING, "new download");
        notifyUpdateChange(downloadId);
        downloadClient.start();
        mWakeLock.acquire();
    }

    @SuppressLint("WakelockTimeout")
    public void resumeDownload(String downloadId) {
        Log.d(TAG, "Resuming " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId) ||
                isVerifyingUpdate(downloadId)) {
            return;
        }
        pauseActiveDownloads();
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry == null) {
            Log.e(TAG, "Could not get download entry");
            return;
        }
        entry.mUpdate = entry.mUpdate.withVerificationFailure(null);
        Update update = entry.mUpdate;
        File file = update.getFile();
        if (file == null || !PartialDownload.exists(file)) {
            Log.e(TAG, "The destination file of " + downloadId + " doesn't exist, can't resume");
            setStatus(entry, UpdateStatus.PAUSED_ERROR, "no file to resume");
            notifyUpdateChange(downloadId);
            return;
        }
        if (isFullyDownloaded(update)) {
            setStatus(entry, UpdateStatus.VERIFYING, "package already complete");
            verifyUpdateAsync(downloadId);
            notifyUpdateChange(downloadId);
        } else if (!hasRoomForDownload(update)) {
            Log.e(TAG, "Not enough free space to resume " + downloadId + " ("
                    + mDownloadRoot.getUsableSpace() + " usable)");
            entry.mUpdate = update.toBuilder()
                    .setStatus(UpdateStatus.PAUSED_ERROR)
                    .setVerificationFailure(PackageVerifier.Failure.NO_SPACE)
                    .build();
            notifyUpdateChange(downloadId);
        } else {
            DownloadClient downloadClient;
            try {
                downloadClient = buildDownloadClient(downloadId, update);
            } catch (IOException exception) {
                Log.e(TAG, "Could not build download client", exception);
                setStatus(entry, UpdateStatus.PAUSED_ERROR, "could not build download client");
                notifyUpdateChange(downloadId);
                return;
            }
            addDownloadClient(entry, downloadClient);
            setStatus(entry, UpdateStatus.STARTING,
                    "resuming at " + file.length() + " of " + update.getFileSize() + " bytes");
            notifyUpdateChange(downloadId);
            downloadClient.resume();
            mWakeLock.acquire();
        }
    }

    public void pauseDownload(String downloadId) {
        Log.d(TAG, "Pausing " + downloadId);
        if (!isDownloading(downloadId)) {
            return;
        }

        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry != null) {
            entry.mDownloadClient.cancel();
            removeDownloadClient(entry);
            synchronized (entry) {
                entry.mUpdate = entry.mUpdate.toBuilder()
                        .setStatus(UpdateStatus.PAUSED)
                        .setEta(0)
                        .setSpeed(0)
                        .build();
            }
            logTransition(downloadId, UpdateStatus.DOWNLOADING, UpdateStatus.PAUSED, null);
            notifyUpdateChange(downloadId);
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.e("UpdaterController", "Could not delete " + file.getAbsolutePath());
        }
    }

    private void deleteUpdateAsync(final Update update) {
        // Unlink right away, so a download or import started right after this can't pick up
        // the file that is being deleted.
        if (update.getFile() != null) {
            PartialDownload.deleteQuietly(update.getFile());
        }
        new Thread(() -> mUpdatesLocalDataSource.removeUpdate(update.getDownloadId())).start();
    }

    public void deleteUpdate(String downloadId) {
        Log.d(TAG, "Deleting update: " + downloadId);
        if (!mDownloads.containsKey(downloadId) || isDownloading(downloadId) ||
                isVerifyingUpdate(downloadId)) {
            return;
        }
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry != null) {
            Update update;
            synchronized (entry) {
                update = entry.mUpdate.toBuilder()
                        .setStatus(UpdateStatus.DELETED)
                        .setProgress(0)
                        .build();
                entry.mUpdate = update;
            }
            deleteUpdateAsync(update);

            if (!update.isAvailableOnline()) {
                Log.d(TAG, "Download no longer available online, removing");
                mDownloads.remove(downloadId);
                notifyUpdateDelete(downloadId);
            } else {
                notifyUpdateChange(downloadId);
            }
        }
    }

    public void cancelDownload(String downloadId) {
        Log.d(TAG, "Cancelling download: " + downloadId);
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry == null) {
            return;
        }
        // Pause the download if it's active
        if (isDownloading(downloadId)) {
            entry.mDownloadClient.cancel();
            removeDownloadClient(entry);
        }
        Update update;
        synchronized (entry) {
            update = entry.mUpdate.toBuilder()
                    .setStatus(UpdateStatus.DELETED)
                    .setProgress(0)
                    .setEta(0)
                    .setSpeed(0)
                    .build();
            entry.mUpdate = update;
        }
        deleteUpdateAsync(update);

        if (!update.isAvailableOnline()) {
            Log.d(TAG, "Download no longer available online, removing");
            mDownloads.remove(downloadId);
            notifyUpdateDelete(downloadId);
        } else {
            notifyUpdateChange(downloadId);
        }
        tryReleaseWakelock();
    }

    /**
     * Copies a user-selected package into the download directory and hands it to the same
     * verification as downloaded packages. A local import never involves the downloader or
     * the network; any previously imported package is replaced.
     *
     * @return false if an import can't start now (one is already running, or the current
     * local package is being verified or installed)
     */
    public boolean importLocalUpdate(Uri uri) {
        if (isInstallingUpdate(Update.LOCAL_ID) || isVerifyingUpdate(Update.LOCAL_ID) ||
                !mImportingLocalUpdate.compareAndSet(false, true)) {
            Log.w(TAG, "Can't import a local update right now");
            return false;
        }
        Log.i(TAG, "Importing local update from " + uri);
        new Thread(() -> {
            final File partFile = new File(mDownloadRoot, LOCAL_UPDATE_FILE_NAME + PARTIAL_SUFFIX);
            final File file = new File(mDownloadRoot, LOCAL_UPDATE_FILE_NAME);
            try {
                copyToFile(uri, partFile);
            } catch (IOException | RuntimeException e) {
                Log.e(TAG, "Could not copy local update from " + uri, e);
                deleteQuietly(partFile);
                finishFailedImport(e instanceof NoSpaceException
                        ? PackageVerifier.Failure.NO_SPACE
                        : PackageVerifier.Failure.IMPORT_FAILED, String.valueOf(e));
                return;
            }

            removeLocalUpdate();
            if (!partFile.renameTo(file)) {
                deleteQuietly(partFile);
                finishFailedImport(PackageVerifier.Failure.IMPORT_FAILED,
                        "could not move " + partFile + " to " + file);
                return;
            }

            final String name = mContext.getString(R.string.local_update_name);
            final Update.Builder builder = new Update.Builder()
                    .setName(name)
                    .setVersion(name)
                    .setFile(file)
                    .setFileSize(file.length())
                    .setStatus(UpdateStatus.VERIFYING);
            try {
                OtaMetadataParser metadata = new OtaMetadataParser(file);
                builder.setTimestamp(metadata.getTimestamp())
                        .setOsPatchLevel(metadata.getSecurityPatchLevel())
                        .setOsSdkLevel(metadata.getSdkLevel());
            } catch (IOException e) {
                Log.e(TAG, "Selected file is not a valid OTA package", e);
                deleteQuietly(file);
                finishFailedImport(PackageVerifier.Failure.CORRUPT, String.valueOf(e));
                return;
            }

            final Update local = builder.build();
            mDownloads.put(Update.LOCAL_ID, new DownloadEntry(local));
            // Persisted like a download in progress, so verification is picked up again if the
            // process dies before it finishes.
            mUpdatesLocalDataSource.addUpdate(local);
            logTransition(Update.LOCAL_ID, null, UpdateStatus.VERIFYING,
                    "imported " + file.length() + " bytes to " + file);
            // Register the verification before clearing the import flag, so the import never
            // looks idle in between.
            verifyUpdateAsync(Update.LOCAL_ID);
            mImportingLocalUpdate.set(false);
            notifyUpdateChange(Update.LOCAL_ID);
        }, "UpdaterImport").start();
        return true;
    }

    public boolean isImportingLocalUpdate() {
        return mImportingLocalUpdate.get();
    }

    private static final class NoSpaceException extends IOException {
        NoSpaceException(String message) {
            super(message);
        }
    }

    private void copyToFile(Uri uri, File destination) throws IOException {
        try (ParcelFileDescriptor pfd = mContext.getContentResolver()
                .openFileDescriptor(uri, "r")) {
            if (pfd == null) {
                throw new IOException("Could not open " + uri);
            }
            final long size = pfd.getStatSize();
            if (size >= 0 && mDownloadRoot.getUsableSpace() < size + MIN_FREE_BYTES) {
                throw new NoSpaceException(size + " bytes to copy, "
                        + mDownloadRoot.getUsableSpace() + " usable");
            }
            // The size can be unknown (e.g. a cloud provider), so keep an eye on the free space
            // and stop before the partition fills up
            final CancellationSignal signal = new CancellationSignal();
            final long[] nextCheck = {SPACE_CHECK_INTERVAL};
            try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor());
                 FileOutputStream out = new FileOutputStream(destination)) {
                android.os.FileUtils.copy(in, out, signal, Runnable::run, progress -> {
                    if (progress >= nextCheck[0]) {
                        nextCheck[0] = progress + SPACE_CHECK_INTERVAL;
                        if (mDownloadRoot.getUsableSpace() < MIN_FREE_BYTES) {
                            signal.cancel();
                        }
                    }
                });
                out.getFD().sync();
            } catch (OperationCanceledException e) {
                throw new NoSpaceException("free space ran low after " + destination.length()
                        + " bytes");
            }
        }
        Log.i(TAG, "Copied " + destination.length() + " bytes from " + uri);
    }

    private void removeLocalUpdate() {
        DownloadEntry previous = mDownloads.remove(Update.LOCAL_ID);
        if (previous != null) {
            deleteQuietly(previous.mUpdate.getFile());
        }
        mUpdatesLocalDataSource.removeUpdate(Update.LOCAL_ID);
        if (previous != null) {
            notifyUpdateDelete(Update.LOCAL_ID);
        }
    }

    private void finishFailedImport(PackageVerifier.Failure failure, String detail) {
        final String name = mContext.getString(R.string.local_update_name);
        // Kept in memory only, so the UI can report why the import failed.
        mDownloads.put(Update.LOCAL_ID, new DownloadEntry(new Update.Builder()
                .setName(name)
                .setVersion(name)
                .setStatus(UpdateStatus.VERIFICATION_FAILED)
                .setVerificationFailure(failure)
                .build()));
        logTransition(Update.LOCAL_ID, null, UpdateStatus.VERIFICATION_FAILED,
                failure + ": " + detail);
        mImportingLocalUpdate.set(false);
        notifyUpdateChange(Update.LOCAL_ID);
    }

    public List<Update> getUpdates() {
        List<Update> updates = new ArrayList<>();
        for (DownloadEntry entry : mDownloads.values()) {
            updates.add(entry.mUpdate);
        }
        return updates;
    }

    public Update getUpdate(String downloadId) {
        DownloadEntry entry = mDownloads.get(downloadId);
        return entry != null ? entry.mUpdate : null;
    }

    public void setUpdate(String downloadId, Update update) {
        DownloadEntry entry = mDownloads.get(downloadId);
        if (entry != null) {
            synchronized (entry) {
                entry.mUpdate = update;
            }
        }
    }

    public boolean isDownloading(String downloadId) {
        //noinspection ConstantConditions
        return mDownloads.containsKey(downloadId) &&
                mDownloads.get(downloadId).mDownloadClient != null;
    }

    public boolean hasActiveDownloads() {
        return mActiveDownloads > 0;
    }

    public boolean isVerifyingUpdate() {
        return !mVerifyingUpdates.isEmpty();
    }

    public boolean isVerifyingUpdate(String downloadId) {
        return mVerifyingUpdates.contains(downloadId);
    }

    public boolean isInstallingUpdate() {
        return UpdateInstaller.isInstalling() ||
                ABUpdateInstaller.isInstallingUpdate(mContext);
    }

    public boolean isInstallingUpdate(String downloadId) {
        return UpdateInstaller.isInstalling(downloadId) ||
                ABUpdateInstaller.isInstallingUpdate(mContext, downloadId);
    }

    public boolean isBusy() {
        return hasActiveDownloads() || isVerifyingUpdate() || isInstallingUpdate() ||
                isImportingLocalUpdate();
    }

    public boolean isInstallingABUpdate() {
        return ABUpdateInstaller.isInstallingUpdate(mContext);
    }

    public boolean isWaitingForReboot(String downloadId) {
        return ABUpdateInstaller.isWaitingForReboot(mContext, downloadId);
    }

    private void pauseActiveDownloads() {
        for (DownloadEntry entry : mDownloads.values()) {
            if (isDownloading(entry.mUpdate.getDownloadId())) {
                pauseDownload(entry.mUpdate.getDownloadId());
            }
        }
    }
    public boolean isFullyDownloaded(Update update) {
        return update.hasFullyDownloadedPackage();
    }
}
