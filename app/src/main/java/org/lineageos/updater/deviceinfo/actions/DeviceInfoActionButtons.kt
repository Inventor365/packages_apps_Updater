/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.deviceinfo.actions

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import org.lineageos.updater.R
import org.lineageos.updater.deviceinfo.DeviceInfoUtils
import org.lineageos.updater.ui.UpdaterActionSurface

@Composable
fun DeviceInfoActionButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfoAction(
            stringResource(R.string.show_changelog),
            Icons.AutoMirrored.Rounded.Article,
            Modifier.weight(1f),
        ) {
            val rawUrl = context.getString(R.string.menu_changelog_url)
            val url = rawUrl
                .replace("{device}", DeviceInfoUtils.device)
                .replace("%1\$s", DeviceInfoUtils.device)
            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
        InfoAction(
            stringResource(R.string.report_issues),
            Icons.Rounded.BugReport,
            Modifier.weight(1f),
        ) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, context.getString(R.string.report_issue_url).toUri())
            )
        }
    }
}

@Composable
private fun InfoAction(title: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    UpdaterActionSurface(
        onClick,
        modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null)
            Text(title, style = MaterialTheme.typography.labelLarge)
        }
    }
}
