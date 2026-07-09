/*
 * Copyright (C) 2025-2026 AxionOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lineageos.updater

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import org.lineageos.updater.controller.UpdaterController
import org.lineageos.updater.controller.UpdaterService
import org.lineageos.updater.misc.Constants
import org.lineageos.updater.misc.Utils
import org.lineageos.updater.model.Update
import org.lineageos.updater.model.UpdateInfo
import org.lineageos.updater.shared.model.UpdaterCallbacks
import org.lineageos.updater.ui.composable.ImportProgressDialog
import org.lineageos.updater.ui.composable.ImportSuccessDialog
import org.lineageos.updater.ui.composable.PreferencesDialog
import org.lineageos.updater.ui.composable.UpdaterApp
import org.lineageos.updater.ui.composable.WelcomeDialog
import org.lineageos.updater.ui.theme.UpdaterTheme
import org.lineageos.updater.ui.viewmodel.UpdaterViewModel

class UpdatesActivity : ComponentActivity(), UpdateImporter.Callbacks {

    private val viewModel: UpdaterViewModel by viewModels()
    private var mUpdaterService: UpdaterService? = null
    private var mUpdateImporter: UpdateImporter? = null
    private var mToBeExported: UpdateInfo? = null

    private val mExportUpdate = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        result.data?.data?.let { exportUpdate(it) }
    }

    private val mImportUpdate = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        mUpdateImporter?.onResult(9061, result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        mUpdateImporter = UpdateImporter(this, this)

        setContent {
            val state by viewModel.uiState

            val callbacks = UpdaterCallbacks(
                onStartDownload = { viewModel.startDownload(it) },
                onPause = { viewModel.pauseDownload(it) },
                onResume = { viewModel.resumeDownload(it) },
                onDelete = { viewModel.deleteUpdate(it) },
                onInstalled = { (getSystemService(Context.POWER_SERVICE) as PowerManager).reboot(null) },
                onVerified = { Utils.triggerUpdate(this@UpdatesActivity, it.downloadId) },
                onFinish = { finish() },
                onRefresh = { viewModel.fetchList(true) },
                onShowPreferences = { viewModel.showPreferences() },
                onImportLocal = { importUpdate() },
                onExportUpdate = { exportUpdate(it) }
            )

            UpdaterTheme {
                UpdaterApp(
                    uiState = state,
                    callbacks = callbacks,
                    changelog = when {
                        state.isLoadingChangelog -> ""
                        else -> state.changelog
                    }
                )

                LaunchedEffect(state.showImportDialog) {
                    if (state.showImportDialog) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                if (state.showImportDialog) ImportProgressDialog(
                    onDismiss = { viewModel.dismissImport(); mUpdateImporter?.stopImport() }
                )
                if (state.showPreferencesDialog) PreferencesDialog(
                    onDismiss = { viewModel.dismissPreferences() },
                    onSave = { viewModel.savePreferences(it) }
                )
                if (state.showWelcomeDialog) WelcomeDialog(
                    onDismiss = { viewModel.dismissWelcome(); maybeShowNotificationPermissionPrompt() }
                )
                state.importSuccessUpdate?.let { update ->
                    ImportSuccessDialog(
                        update = update,
                        onInstall = {
                            viewModel.getUpdatesList()
                            Utils.triggerUpdate(this, update.downloadId)
                            viewModel.clearImportSuccess()
                        },
                        onCancel = {
                            viewModel.deleteImportedUpdate(update.downloadId)
                        }
                    )
                }
            }
            state.toastMessage?.let {
                LaunchedEffect(it) {
                    Toast.makeText(this@UpdatesActivity, it, Toast.LENGTH_SHORT).show()
                    viewModel.clearToast()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, UpdaterService::class.java)
        startService(intent)
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE)
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver, IntentFilter().apply {
            addAction(UpdaterController.ACTION_UPDATE_STATUS)
            addAction(UpdaterController.ACTION_DOWNLOAD_PROGRESS)
            addAction(UpdaterController.ACTION_INSTALL_PROGRESS)
            addAction(UpdaterController.ACTION_UPDATE_REMOVED)
        })
    }

    override fun onPause() {
        if (viewModel.uiState.value.showImportDialog) {
            viewModel.dismissImport()
            mUpdateImporter?.stopImport()
        }
        super.onPause()
    }

    override fun onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBroadcastReceiver)
        mUpdaterService?.let { unbindService(mConnection) }
        super.onStop()
    }

    private val mConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            mUpdaterService = (service as UpdaterService.LocalBinder).service
            viewModel.setController(mUpdaterService?.updaterController)
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            mUpdaterService = null
            viewModel.setController(null)
        }
    }

    private val mBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val downloadId = intent.getStringExtra(UpdaterController.EXTRA_DOWNLOAD_ID)
            when (intent.action) {
                UpdaterController.ACTION_UPDATE_STATUS -> {
                    viewModel.onDlStateChange(downloadId)
                    viewModel.checkUpdates()
                }
                UpdaterController.ACTION_DOWNLOAD_PROGRESS -> {
                    viewModel.handleDownloadProgress(
                        intent.getFloatExtra(UpdaterController.EXTRA_PROGRESS, 0f),
                        intent.getLongExtra(UpdaterController.EXTRA_DOWNLOADED_BYTES, 0L),
                        intent.getLongExtra(UpdaterController.EXTRA_TOTAL_BYTES, 0L)
                    )
                }
                UpdaterController.ACTION_INSTALL_PROGRESS -> {
                    viewModel.handleInstallProgress(
                        intent.getFloatExtra(UpdaterController.EXTRA_PROGRESS, 0f),
                        intent.getIntExtra(UpdaterController.EXTRA_INSTALL_PROGRESS, 0)
                    )
                }
                UpdaterController.ACTION_UPDATE_REMOVED -> viewModel.handleUpdateRemoved()
            }
        }
    }

    override fun onImportStarted() {
        viewModel.onImportStarted()
    }

    override fun onImportCompleted(update: Update?) {
        viewModel.onImportCompleted(update)
    }

    private fun exportUpdate(update: UpdateInfo) {
        mToBeExported = update
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, update.name)
        }
        mExportUpdate.launch(intent)
    }

    private fun importUpdate() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
        }
        mImportUpdate.launch(intent)
    }

    private fun exportUpdate(uri: Uri) {
        startService(Intent(this, ExportUpdateService::class.java).apply {
            action = ExportUpdateService.ACTION_START_EXPORTING
            putExtra(ExportUpdateService.EXTRA_SOURCE_FILE, mToBeExported?.file)
            putExtra(ExportUpdateService.EXTRA_DEST_URI, uri)
        })
    }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            viewModel.showToast(getString(
                if (granted) R.string.notifications_enabled else R.string.notifications_denied
            ))
            markNotificationPermissionRequested()
        }

    private fun maybeShowNotificationPermissionPrompt() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val requested = prefs.getBoolean(Constants.HAS_REQUESTED_NOTIFICATION_PERMISSION, false)
        if (!requested) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                markNotificationPermissionRequested()
            }
        }
    }

    private fun markNotificationPermissionRequested() {
        PreferenceManager.getDefaultSharedPreferences(this)
            .edit()
            .putBoolean(Constants.HAS_REQUESTED_NOTIFICATION_PERMISSION, true)
            .apply()
    }
}
