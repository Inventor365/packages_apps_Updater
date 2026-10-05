/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-FileCopyrightText: Lunaris AOSP Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.util

import android.os.RecoverySystem
import android.util.Log
import androidx.annotation.StringRes
import org.lineageos.updater.R
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SignatureException

/**
 * Verifies an OTA package that is already on local storage. Downloaded and imported packages
 * both go through here, so where a package came from never changes what is checked.
 *
 * Every check that has the data it needs is mandatory, cheapest first:
 * 1. size, when the server published one;
 * 2. zip structure and OTA metadata (see [OtaMetadataParser]);
 * 3. SHA-256, when the server published one;
 * 4. whole-package signature against /system/etc/security/otacerts.zip, the same trust anchor
 *    update_engine uses for the payload signature on A/B devices.
 */
object PackageVerifier {
    private const val TAG = "PackageVerifier"

    // Messages thrown by RecoverySystem.verifyPackage()
    private const val MSG_UNTRUSTED_KEY = "signature doesn't match any trusted key"
    private const val MSG_INTERRUPTED = "verification was interrupted"

    enum class Failure(
        @param:StringRes val messageRes: Int,
        /** The bytes on disk are still a usable prefix, so the package must not be deleted. */
        val keepsPackage: Boolean = false,
    ) {
        IMPORT_FAILED(R.string.verification_error_import),
        MISSING(R.string.verification_error_missing),
        INCOMPLETE(R.string.verification_error_incomplete, keepsPackage = true),
        CORRUPT(R.string.verification_error_corrupt),
        HASH_MISMATCH(R.string.verification_error_hash),
        UNTRUSTED_KEY(R.string.verification_error_untrusted_key),
        BAD_SIGNATURE(R.string.verification_error_signature),
        INTERRUPTED(R.string.verification_error_interrupted, keepsPackage = true),
    }

    data class Result(val failure: Failure?, val detail: String) {
        val isVerified: Boolean
            get() = failure == null
    }

    @JvmStatic
    fun verify(
        file: File?,
        expectedSize: Long,
        expectedSha256: String?,
        checkSignature: Boolean,
    ): Result {
        if (file == null || !file.isFile) {
            return fail(Failure.MISSING, "no package at $file")
        }

        val actualSize = file.length()
        Log.i(
            TAG, "Verifying $file: size=$actualSize expectedSize=$expectedSize " +
                    "expectedSha256=${expectedSha256 ?: "<none>"} checkSignature=$checkSignature"
        )
        if (expectedSize > 0 && actualSize < expectedSize) {
            return fail(Failure.INCOMPLETE, "$actualSize of $expectedSize bytes")
        }
        if (expectedSize > 0 && actualSize > expectedSize) {
            return fail(Failure.CORRUPT, "$actualSize bytes, expected $expectedSize")
        }

        try {
            val metadata = OtaMetadataParser(file)
            Log.i(
                TAG, "OTA metadata: ab=${metadata.isABUpdate} timestamp=${metadata.timestamp} " +
                        "sdk=${metadata.sdkLevel} spl=${metadata.securityPatchLevel}"
            )
        } catch (e: IOException) {
            return fail(Failure.CORRUPT, e.message ?: "invalid OTA package", e)
        }

        if (expectedSha256 != null) {
            val actualSha256 = FileUtils.calculateHash(file, "SHA-256")
                ?: return fail(Failure.CORRUPT, "could not read package")
            Log.i(TAG, "SHA-256 expected=$expectedSha256 actual=$actualSha256")
            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                return fail(Failure.HASH_MISMATCH, "expected $expectedSha256, got $actualSha256")
            }
        }

        if (checkSignature) {
            try {
                RecoverySystem.verifyPackage(file, null, null)
            } catch (e: SignatureException) {
                val failure = when (e.message) {
                    MSG_UNTRUSTED_KEY -> Failure.UNTRUSTED_KEY
                    MSG_INTERRUPTED -> Failure.INTERRUPTED
                    else -> Failure.BAD_SIGNATURE
                }
                return fail(failure, e.message ?: "signature verification failed", e)
            } catch (e: GeneralSecurityException) {
                return fail(Failure.BAD_SIGNATURE, e.message ?: "signature verification failed", e)
            } catch (e: IOException) {
                return fail(Failure.CORRUPT, e.message ?: "could not read package signature", e)
            }
            Log.i(TAG, "Package signature verified against device OTA certificates")
        }

        Log.i(TAG, "Package verified: $file")
        return Result(null, "verified")
    }

    private fun fail(failure: Failure, detail: String, cause: Exception? = null): Result {
        Log.e(TAG, "Verification failed: $failure ($detail)", cause)
        return Result(failure, detail)
    }
}
