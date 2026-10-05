/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.lineageos.updater.updates

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.lineageos.updater.R
import org.lineageos.updater.ui.CollapseBar
import org.lineageos.updater.ui.UpdaterSectionTitle
import org.lineageos.updater.updates.action.UpdateAction
import org.lineageos.updater.updates.state.UpdateItemState

@Composable
fun UpdateList(
    items: List<UpdateItemState>,
    isUpdatesCheckStatusVisible: Boolean,
    onAction: (UpdateAction, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        if (!isUpdatesCheckStatusVisible) {
            UpdateZeroStatePreference(
                modifier = modifier,
                text = stringResource(R.string.updates_zero_state_title),
                description = stringResource(R.string.updates_zero_state_description),
            )
        }
        return
    }

    var expanded by rememberSaveable { mutableStateOf(false) }
    var expandedItemIds by
        rememberSaveable(items.first().downloadId) {
            mutableStateOf(setOf(items.first().downloadId))
        }
    // Updates with an operation in progress or a package on disk (downloaded or imported) must
    // stay reachable when collapsed; otherwise an imported package can sit hidden behind a
    // remote update of the same build that only offers a download.
    val activeItems = items.filter { it.progress != null || it.isLocal }
    val collapsedVisibleItems = (listOf(items.first()) + activeItems).distinctBy { it.downloadId }
    val hiddenItemCount = items.size - collapsedVisibleItems.size
    val visibleItemIds =
        if (expanded) {
            items.map { it.downloadId }.toSet()
        } else {
            collapsedVisibleItems.map { it.downloadId }.toSet()
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UpdaterSectionTitle(stringResource(R.string.updater_available_builds))
        items.forEach { item ->
            key(item.downloadId) {
                AnimatedVisibility(
                    visible = item.downloadId in visibleItemIds,
                    enter =
                        fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                            expandVertically(
                                MaterialTheme.motionScheme.defaultSpatialSpec(),
                                expandFrom = Alignment.Top,
                            ),
                    exit =
                        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                            shrinkVertically(
                                MaterialTheme.motionScheme.fastSpatialSpec(),
                                shrinkTowards = Alignment.Top,
                            ),
                ) {
                    val staysExpanded = item.progress != null
                    val itemExpanded = staysExpanded || (item.downloadId in expandedItemIds)
                    val onExpandToggle =
                        if (staysExpanded) {
                            null
                        } else {
                            {
                                expandedItemIds =
                                    if (item.downloadId in expandedItemIds) {
                                        expandedItemIds - item.downloadId
                                    } else {
                                        expandedItemIds + item.downloadId
                                    }
                            }
                        }

                    UpdateItem(
                        state = item,
                        expanded = itemExpanded,
                        onExpandToggle = onExpandToggle,
                        onAction = { action -> onAction(action, item.downloadId) },
                    )
                }
            }
        }

        AnimatedVisibility(visible = hiddenItemCount > 0) {
            CollapseBar(
                expanded = expanded,
                hiddenItemCount = hiddenItemCount,
                onExpandedChange = {
                    expanded = it
                    if (!it)
                        expandedItemIds = expandedItemIds.intersect(setOf(items.first().downloadId))
                },
            )
        }
    }
}
