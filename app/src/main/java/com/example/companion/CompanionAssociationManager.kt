package com.example.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CompanionTrustStatus(
    val hasCompanionAssociation: Boolean = false,
    val associationCount: Int = 0,
    val sensitivePermissionDeclaredOrGranted: Boolean = false,
    val appOpsSensitiveAllowed: Boolean = false,
    val isAndroid15Plus: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
    val lastStatusMessage: String = ""
)

@Singleton
class CompanionAssociationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _status = MutableStateFlow(readCurrentStatus())
    val status: StateFlow<CompanionTrustStatus> = _status.asStateFlow()

    fun refreshStatus(message: String = _status.value.lastStatusMessage): CompanionTrustStatus {
        val updated = readCurrentStatus(message)
        _status.value = updated
        return updated
    }

    @Suppress("DEPRECATION")
    private fun readCurrentStatus(message: String = ""): CompanionTrustStatus {
        val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        val count = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm?.myAssociations?.size ?: 0
            } else {
                cdm?.associations?.size ?: 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query CompanionDeviceManager associations: ${e.message}")
            0
        }

        val permGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECEIVE_SENSITIVE_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val appOpsAllowed = checkSensitiveNotifAppOp()

        return CompanionTrustStatus(
            hasCompanionAssociation = count > 0,
            associationCount = count,
            sensitivePermissionDeclaredOrGranted = permGranted,
            appOpsSensitiveAllowed = appOpsAllowed,
            isAndroid15Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM,
            lastStatusMessage = message
        )
    }

    private fun checkSensitiveNotifAppOp(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = appOps.unsafeCheckOpNoThrow(
                "android:receive_sensitive_notifications",
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.d(TAG, "AppOps check unavailable: ${e.message}")
            false
        }
    }

    /**
     * Initiates a CompanionDeviceManager association request.
     * On Android 15+ (API 35), holding an active CompanionDeviceManager association
     * earns the trusted-listener exemption from OTP/sensitive notification redaction
     * when combined with RECEIVE_SENSITIVE_NOTIFICATIONS in the manifest.
     */
    @SuppressLint("MissingPermission")
    fun startAssociation(
        onIntentSenderReady: (IntentSender) -> Unit,
        onAssociated: () -> Unit,
        onError: (String) -> Unit
    ) {
        val cdm = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as? CompanionDeviceManager
        if (cdm == null) {
            onError("CompanionDeviceManager is not available on this device.")
            return
        }

        val requestBuilder = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .setSingleDevice(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBuilder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)
        }

        val request = requestBuilder.build()

        val callback = object : CompanionDeviceManager.Callback() {
            @Deprecated("Deprecated in API 33")
            override fun onDeviceFound(chooserLauncher: IntentSender) {
                onIntentSenderReady(chooserLauncher)
            }

            override fun onAssociationPending(intentSender: IntentSender) {
                onIntentSenderReady(intentSender)
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                refreshStatus("Companion association created (id=${associationInfo.id}).")
                onAssociated()
            }

            override fun onFailure(error: CharSequence?) {
                val msg = error?.toString().orEmpty().ifBlank {
                    "No nearby companion device selected or pairing timed out."
                }
                refreshStatus(msg)
                onError(msg)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                cdm.associate(request, context.mainExecutor, callback)
            } else {
                cdm.associate(request, callback, null)
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "CompanionDeviceManager associate() failed"
            refreshStatus(msg)
            onError(msg)
        }
    }

    companion object {
        private const val TAG = "CompanionAssocMgr"
    }
}
