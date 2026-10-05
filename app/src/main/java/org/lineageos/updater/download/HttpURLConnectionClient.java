/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.updater.download;

import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpURLConnectionClient implements DownloadClient {

    private final static String TAG = "HttpURLConnectionClient";

    // Ref: mozilla-mobile/firefox-android AbstractFetchDownloadService.CHUNK_SIZE
    private static final int CHUNK_SIZE = 32 * 1024;
    private static final int MAX_REDIRECTS = 10;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    // Only one thread may write a given destination at a time. A resumed download waits here
    // for the previous (cancelled) writer to exit before measuring the file for its Range.
    private static final Map<String, Thread> sWriters = new ConcurrentHashMap<>();

    private volatile HttpURLConnection mClient;

    private final File mDestination;
    private final DownloadClient.ProgressListener mProgressListener;
    private final DownloadClient.DownloadCallback mCallback;
    private final boolean mUseDuplicateLinks;

    private DownloadThread mDownloadThread;

    public class Headers implements DownloadClient.Headers {
        @Override
        public String get(String name) {
            return mClient.getHeaderField(name);
        }
    }

    HttpURLConnectionClient(String url, File destination,
            DownloadClient.ProgressListener progressListener,
            DownloadClient.DownloadCallback callback,
            boolean useDuplicateLinks) throws IOException {
        mClient = (HttpURLConnection) new URL(url).openConnection();
        setupDefaultConnectionProperties(mClient);
        mDestination = destination;
        mProgressListener = progressListener;
        mCallback = callback;
        mUseDuplicateLinks = useDuplicateLinks;
    }

    private static void setupDefaultConnectionProperties(HttpURLConnection connection) {
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "LunarisUpdater/1.0");
        // Transparent gzip would strip Content-Length and break byte ranges for resuming.
        connection.setRequestProperty("Accept-Encoding", "identity");
    }

    @Override
    public void start() {
        if (mDownloadThread != null) {
            Log.e(TAG, "Already downloading");
            return;
        }
        downloadFileInternalCommon(false);
    }

    @Override
    public void resume() {
        if (mDownloadThread != null) {
            Log.e(TAG, "Already downloading");
            return;
        }
        downloadFileResumeInternal();
    }

    @Override
    public void cancel() {
        if (mDownloadThread == null) {
            Log.e(TAG, "Not downloading");
            return;
        }
        mDownloadThread.interrupt();
        mDownloadThread = null;
        // Unblock a read stuck on the socket. Closing a TLS socket may touch the network,
        // so don't do it on the caller's (UI) thread.
        final HttpURLConnection client = mClient;
        new Thread(client::disconnect, "UpdaterDownloadCancel").start();
    }

    private void downloadFileResumeInternal() {
        if (!mDestination.exists()) {
            mCallback.onFailure(false);
            return;
        }
        // The Range offset is taken by the download thread once it owns the destination.
        downloadFileInternalCommon(true);
    }

    private void downloadFileInternalCommon(boolean resume) {
        if (mDownloadThread != null) {
            Log.wtf(TAG, "Already downloading");
            return;
        }

        mDownloadThread = new DownloadThread(resume);
        mDownloadThread.start();
    }

    private static boolean isSuccessCode(int statusCode) {
        return (statusCode / 100) == 2;
    }

    private static boolean isRedirectCode(int statusCode) {
        return (statusCode / 100) == 3;
    }

    private static boolean isPartialContentCode(int statusCode) {
        return statusCode == 206;
    }

    private class DownloadThread extends Thread {

        private long mTotalBytes = 0;
        private long mTotalBytesRead = 0;

        private long mCurSampleBytes = 0;
        private long mLastMillis = 0;
        private long mSpeed = -1;
        private long mEta = -1;
        private double mLastEta = -1;

        private final boolean mResume;

        private DownloadThread(boolean resume) {
            mResume = resume;
        }

        private void calculateSpeed(boolean justResumed) {
            final long millis = SystemClock.elapsedRealtime();
            if (justResumed) {
                mLastMillis = millis;
                mSpeed = -1;
                mLastEta = -1;
                mCurSampleBytes = mTotalBytesRead;
                return;
            }
            final long delta = millis - mLastMillis;
            if (delta > 500) {
                final long curSpeed = ((mTotalBytesRead - mCurSampleBytes) * 1000) / delta;
                if (mSpeed == -1) {
                    mSpeed = curSpeed;
                } else {
                    mSpeed = ((mSpeed * 3) + curSpeed) / 4;
                }

                mLastMillis = millis;
                mCurSampleBytes = mTotalBytesRead;
            }
        }

        private void calculateEta() {
            if (mSpeed <= 0) return;

            double rawSeconds = (double) (mTotalBytes - mTotalBytesRead) / mSpeed;

            if (mLastEta >= 0 && rawSeconds > mLastEta / 2) {
                double diff = rawSeconds - mLastEta;
                rawSeconds = mLastEta + (diff < 0 ? 0.3 : 0.1) * diff;

                diff = rawSeconds - mLastEta;
                double diffPct = (diff / mLastEta) * 100;
                if (Math.abs(diff) < 5 || Math.abs(diffPct) < 5) {
                    rawSeconds = mLastEta - (diff < 0 ? 0.4 : 0.2);
                }
            }

            mLastEta = Math.max(rawSeconds, 1.0);
            mEta = (long) mLastEta;
        }

        private void changeClientUrl(URL newUrl) throws IOException {
            String range = mClient.getRequestProperty("Range");
            mClient.disconnect();
            mClient = (HttpURLConnection) newUrl.openConnection();
            setupDefaultConnectionProperties(mClient);
            if (range != null) {
                mClient.setRequestProperty("Range", range);
            }
        }

        private static class DuplicateLink {
            final String mUrl;
            final int mPriority;
            DuplicateLink(String url, int priority) {
                mUrl = url;
                mPriority = priority;
            }
        }

        private void collectDuplicateLinks(PriorityQueue<DuplicateLink> duplicates) {
            for (Map.Entry<String, List<String>> entry : mClient.getHeaderFields().entrySet()) {
                if ("Link".equalsIgnoreCase(entry.getKey())) {
                    String regex = "(?i)<([^>]+)>\\s*;\\s*rel=duplicate(?:.*pri=([0-9]+).*|.*)?";
                    Pattern pattern = Pattern.compile(regex);
                    for (String field : entry.getValue()) {
                        Matcher matcher = pattern.matcher(field);
                        if (matcher.matches()) {
                            String url = matcher.group(1);
                            String pri = matcher.group(2);
                            int priority = pri != null ? Integer.parseInt(pri) : 999999;
                            duplicates.add(new DuplicateLink(url, priority));
                            Log.d(TAG, "Adding duplicate link " + url);
                        }
                    }
                }
            }
        }

        private int followRedirectsAndDuplicates() throws IOException {
            PriorityQueue<DuplicateLink> duplicates = new PriorityQueue<>(
                    Comparator.comparingInt(d -> d.mPriority));

            int redirectCount = 0;
            while (true) {
                mClient.setInstanceFollowRedirects(false);
                mClient.connect();
                int responseCode = mClient.getResponseCode();

                if (mUseDuplicateLinks) {
                    collectDuplicateLinks(duplicates);
                }

                if (isRedirectCode(responseCode)) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        if (!duplicates.isEmpty()) {
                            DuplicateLink fallback = duplicates.poll();
                            Log.w(TAG, "Exceeded redirects, falling back to duplicate link: " + fallback.mUrl);
                            changeClientUrl(new URL(fallback.mUrl));
                            redirectCount = 0;
                            continue;
                        }
                        throw new IOException("Too many redirects: " + redirectCount);
                    }

                    String location = mClient.getHeaderField("Location");
                    if (location == null || location.isEmpty()) {
                        throw new IOException("Redirect with missing Location header");
                    }

                    URL currentUrl = mClient.getURL();
                    URL nextUrl = new URL(currentUrl, location);
                    Log.d(TAG, "Following redirect (" + responseCode + ") to " + nextUrl);

                    if (org.lineageos.updater.util.SourceForgeMirrorUtils.INSTANCE.isSourceForgeDirectMirrorUrl(nextUrl)) {
                        List<URL> candidates = org.lineageos.updater.util.SourceForgeMirrorUtils.INSTANCE.getMirrorCandidateUrls(nextUrl);
                        URL fastest = org.lineageos.updater.util.SourceForgeMirrorUtils.INSTANCE.selectFastestMirror(candidates);
                        List<URL> prioritized = new java.util.ArrayList<>();
                        prioritized.add(fastest);
                        for (URL c : candidates) {
                            if (!prioritized.contains(c)) {
                                prioritized.add(c);
                            }
                        }

                        for (URL candidate : prioritized) {
                            try {
                                changeClientUrl(candidate);
                                mClient.setInstanceFollowRedirects(false);
                                mClient.connect();
                                int mirrorCode = mClient.getResponseCode();
                                if (isSuccessCode(mirrorCode) || isPartialContentCode(mirrorCode)) {
                                    Log.d(TAG, "Successfully connected to fast SourceForge mirror: " + candidate.getHost());
                                    return mirrorCode;
                                } else {
                                    Log.d(TAG, "Mirror " + candidate.getHost() + " replied with " + mirrorCode + ", trying next mirror");
                                }
                            } catch (IOException e) {
                                Log.w(TAG, "Failed connecting to mirror " + candidate.getHost(), e);
                            }
                        }
                    }

                    changeClientUrl(nextUrl);
                    redirectCount++;
                    continue;
                }

                if (!isSuccessCode(responseCode) && !isPartialContentCode(responseCode)) {
                    if (!duplicates.isEmpty()) {
                        DuplicateLink fallback = duplicates.poll();
                        Log.w(TAG, "Server replied with " + responseCode + ", trying duplicate link: " + fallback.mUrl);
                        changeClientUrl(new URL(fallback.mUrl));
                        redirectCount = 0;
                        continue;
                    }
                }

                return responseCode;
            }
        }

        @Override
        public void run() {
            final String destinationKey = mDestination.getAbsolutePath();
            final Thread previousWriter = sWriters.put(destinationKey, this);
            boolean justResumed = false;
            try {
                if (previousWriter != null) {
                    Log.d(TAG, "Waiting for previous writer of " + mDestination.getName());
                    previousWriter.join();
                }

                long offset = 0;
                if (mResume) {
                    offset = mDestination.length();
                    mClient.setRequestProperty("Range", "bytes=" + offset + "-");
                }

                int responseCode = followRedirectsAndDuplicates();
                if (isInterrupted()) {
                    // Paused or cancelled while resolving redirects/mirrors
                    mCallback.onFailure(true);
                    return;
                }

                final long contentLength = mClient.getContentLengthLong();
                Log.i(TAG, "Downloading " + mDestination.getName() + " from "
                        + mClient.getURL().getHost() + mClient.getURL().getPath()
                        + ": code=" + responseCode + " contentLength=" + contentLength
                        + " offset=" + offset);

                mCallback.onResponse(new Headers());

                if (mResume && isPartialContentCode(responseCode)) {
                    justResumed = true;
                    mTotalBytesRead = offset;
                    Log.d(TAG, "The server fulfilled the partial content request");
                } else if (mResume || !isSuccessCode(responseCode)) {
                    Log.e(TAG, "The server replied with code " + responseCode);
                    mCallback.onFailure(isInterrupted());
                    return;
                }

                try (
                        InputStream inputStream = mClient.getInputStream();
                        OutputStream outputStream = new FileOutputStream(mDestination, mResume)
                ) {
                    mTotalBytes = contentLength >= 0 ? contentLength + mTotalBytesRead : -1;
                    byte[] b = new byte[CHUNK_SIZE];
                    int count;
                    while (!isInterrupted() && (count = inputStream.read(b)) > 0) {
                        outputStream.write(b, 0, count);
                        mTotalBytesRead += count;
                        calculateSpeed(justResumed);
                        calculateEta();
                        justResumed = false;
                        if (mProgressListener != null) {
                            mProgressListener.update(mTotalBytesRead, mTotalBytes, mSpeed, mEta);
                        }
                    }
                    if (mProgressListener != null) {
                        mProgressListener.update(mTotalBytesRead, mTotalBytes, mSpeed, mEta);
                    }

                    outputStream.flush();

                    if (isInterrupted()) {
                        mCallback.onFailure(true);
                    } else if (mTotalBytes >= 0 && mTotalBytesRead != mTotalBytes) {
                        // End of stream is not completion: never hand a short file to
                        // verification. The partial file stays on disk for resuming.
                        throw new IOException("Stream ended after " + mTotalBytesRead
                                + " of " + mTotalBytes + " bytes");
                    } else {
                        Log.i(TAG, "Downloaded " + mTotalBytesRead + " bytes to "
                                + mDestination);
                        mCallback.onSuccess();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error downloading file", e);
                mCallback.onFailure(isInterrupted());
            } catch (InterruptedException e) {
                Log.d(TAG, "Cancelled while waiting for previous writer");
                mCallback.onFailure(true);
            } finally {
                sWriters.remove(destinationKey, this);
                mClient.disconnect();
            }
        }
    }
}
