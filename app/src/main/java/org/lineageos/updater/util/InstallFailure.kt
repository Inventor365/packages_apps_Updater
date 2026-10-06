/*
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.os.UpdateEngine.ErrorCodeConstants
import androidx.annotation.StringRes
import org.lineageos.updater.R

/** Why installing an update failed, in terms the user can act on. */
enum class InstallFailure(@param:StringRes val messageRes: Int) {
    /** The package couldn't be read or handed to update_engine. */
    PREPARE(R.string.install_error_prepare),
    SPACE(R.string.install_error_space),
    DOWNGRADE(R.string.install_error_downgrade),
    SECURITY_PATCH(R.string.install_error_security_patch),
    SIGNATURE(R.string.install_error_signature),
    PACKAGE(R.string.install_error_package),
    POSTINSTALL(R.string.install_error_postinstall),
    DEVICE(R.string.install_error_device),
    BUSY(R.string.install_error_busy),
    GENERIC(R.string.install_error_generic);

    companion object {
        /** kUserCanceled: the install was cancelled, which isn't a failure. */
        const val ERROR_USER_CANCELED = 48

        /** kUpdateAlreadyInstalled: applyPayload() of an update that is waiting for a reboot. */
        const val ERROR_UPDATE_ALREADY_INSTALLED = 66

        // update_engine ErrorCode values (system/update_engine/common/error_code.h); only a few
        // of them are in ErrorCodeConstants
        private const val ERROR_UPDATE_PROCESSING = 65
        private val SIGNATURE_ERRORS = setOf(12, 18, 24, 25, 26, 33, 39)
        private val PACKAGE_ERRORS =
            setOf(6, 9, 10, 11, 14, 17, 21, 22, 23, 27, 28, 29, 32, 38, 44, 45, 62)
        private val POSTINSTALL_ERRORS = setOf(5, 41, 63)
        private val DEVICE_ERRORS = setOf(4, 7, 8, 13, 15, 16, 47, 56, 61, 64)

        /** Flags update_engine can set in the top bits of a code. */
        private const val FLAG_BITS = 0xF0000000.toInt()

        @JvmStatic
        fun fromErrorCode(errorCode: Int): InstallFailure =
            when (val code = errorCode and FLAG_BITS.inv()) {
                ErrorCodeConstants.NOT_ENOUGH_SPACE -> SPACE
                ErrorCodeConstants.PAYLOAD_TIMESTAMP_ERROR -> DOWNGRADE
                ERROR_UPDATE_PROCESSING -> BUSY
                in SIGNATURE_ERRORS -> SIGNATURE
                in PACKAGE_ERRORS -> PACKAGE
                in POSTINSTALL_ERRORS -> POSTINSTALL
                in DEVICE_ERRORS -> DEVICE
                else -> GENERIC
            }

        @JvmStatic
        fun isUserCanceled(errorCode: Int) = (errorCode and FLAG_BITS.inv()) == ERROR_USER_CANCELED
    }
}
