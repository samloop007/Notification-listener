package com.example.ui.child

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.companion.CompanionAssociationManager
import com.example.companion.CompanionTrustStatus
import com.example.data.local.CapturedNotificationDao
import com.example.data.local.CapturedNotificationEntity
import com.example.data.local.PersistedAppState
import com.example.data.local.RolePreferences
import com.example.data.remote.FamilyRepository
import com.example.oem.BatteryOptimizationHelper
import com.example.service.ListenerHealthRegistry
import com.example.service.ListenerHealthState
import com.example.service.NotifListener
import com.example.service.SyncWorker
import com.example.service.WatchdogService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChildDashboardUiState(
    val prefs: PersistedAppState = PersistedAppState(),
    val listenerHealth: ListenerHealthState = ListenerHealthState(),
    val companionStatus: CompanionTrustStatus = CompanionTrustStatus(),
    val ignoringBatteryOptimizations: Boolean = false,
    val pendingQueueCount: Int = 0,
    val totalCapturedCount: Int = 0,
    val recentCaptures: List<CapturedNotificationEntity> = emptyList(),
    val snackbarMessage: String? = null
)

@HiltViewModel
class ChildViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val dao: CapturedNotificationDao,
    private val rolePreferences: RolePreferences,
    private val repository: FamilyRepository,
    val companionAssociationManager: CompanionAssociationManager
) : ViewModel() {

    private val batteryIgnoringFlow = MutableStateFlow(
        BatteryOptimizationHelper.isIgnoringBatteryOptimizations(appContext)
    )
    private val snackbarFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ChildDashboardUiState> = combine(
        rolePreferences.stateFlow,
        ListenerHealthRegistry.state,
        companionAssociationManager.status,
        batteryIgnoringFlow,
        dao.observePendingCount(),
        dao.observeTotalCount(),
        dao.observeRecentCaptures(),
        snackbarFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        ChildDashboardUiState(
            prefs = values[0] as PersistedAppState,
            listenerHealth = values[1] as ListenerHealthState,
            companionStatus = values[2] as CompanionTrustStatus,
            ignoringBatteryOptimizations = values[3] as Boolean,
            pendingQueueCount = values[4] as Int,
            totalCapturedCount = values[5] as Int,
            recentCaptures = values[6] as List<CapturedNotificationEntity>,
            snackbarMessage = values[7] as String?
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = ChildDashboardUiState()
    )

    fun refreshDiagnostics() {
        val enabled = NotifListener.isNotificationAccessEnabled(appContext)
        val instance = NotifListener.currentInstance()
        val activeOk = instance?.canReadActiveNotifications() == true
        ListenerHealthRegistry.update {
            it.copy(
                permissionGranted = enabled,
                serviceBound = instance != null,
                activeNotificationsOk = activeOk,
                lastCheckAtMs = System.currentTimeMillis()
            )
        }
        batteryIgnoringFlow.value = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(appContext)
        val trust = companionAssociationManager.refreshStatus()

        viewModelScope.launch {
            rolePreferences.setCompanionAssociated(trust.hasCompanionAssociation)
            val prefs = rolePreferences.current()
            if (prefs.familyId.isNotBlank()) {
                repository.updateChildHealthStatus(
                    familyId = prefs.familyId,
                    listenerActive = enabled && activeOk,
                    companionTrusted = trust.hasCompanionAssociation || trust.appOpsSensitiveAllowed
                )
            }
            if (enabled) {
                WatchdogService.start(appContext)
            }
        }
    }

    fun requestRebind() {
        NotifListener.requestSystemRebind(appContext)
        snackbarFlow.value = "Requested OS rebind for NotifListener."
    }

    fun triggerSyncNow() {
        SyncWorker.enqueue(appContext)
        snackbarFlow.value = "Queued batch sync to Firestore."
    }

    fun onCompanionAssociated() {
        val trust = companionAssociationManager.refreshStatus("Companion association active.")
        viewModelScope.launch {
            rolePreferences.setCompanionAssociated(trust.hasCompanionAssociation)
            val prefs = rolePreferences.current()
            if (prefs.familyId.isNotBlank()) {
                repository.updateChildHealthStatus(
                    familyId = prefs.familyId,
                    listenerActive = NotifListener.isNotificationAccessEnabled(appContext),
                    companionTrusted = true
                )
            }
        }
        snackbarFlow.value = "CompanionDeviceManager trusted exemption active."
    }

    fun postMessage(message: String) {
        snackbarFlow.value = message
    }

    fun clearMessage() {
        snackbarFlow.value = null
    }
}
