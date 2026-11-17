package com.example.ui.parent

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.RolePreferences
import com.example.data.model.FamilyMember
import com.example.data.model.ListenerAlert
import com.example.data.model.MonitoredApp
import com.example.data.model.NotificationRecord
import com.example.data.remote.FamilyRepository
import com.google.firebase.firestore.FirebaseFirestoreException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

sealed interface FeedDataState {
    data object Loading : FeedDataState
    data class Ready(val records: List<NotificationRecord>) : FeedDataState
    data class Error(val message: String) : FeedDataState
}

data class DayGroupedNotifications(
    val dayKey: String,
    val displayHeader: String,
    val items: List<NotificationRecord>
)

data class ParentFeedUiState(
    val familyId: String = "",
    val familyName: String = "",
    val selectedAppFilter: MonitoredApp? = null,
    val searchQuery: String = "",
    val feedState: FeedDataState = FeedDataState.Loading,
    val groupedDays: List<DayGroupedNotifications> = emptyList(),
    val unacknowledgedAlerts: List<ListenerAlert> = emptyList(),
    val childMembers: List<FamilyMember> = emptyList()
)

@HiltViewModel
class ParentFeedViewModel @Inject constructor(
    private val repository: FamilyRepository,
    private val rolePreferences: RolePreferences
) : ViewModel() {

    private val selectedAppFilter = MutableStateFlow<MonitoredApp?>(null)
    private val searchQuery = MutableStateFlow("")

    private val familyBindingFlow = rolePreferences.stateFlow
        .map { it.familyId to it.familyName }
        .distinctUntilChanged()

    init {
        viewModelScope.launch {
            try {
                val (famId, famName) = repository.ensureParentFamily()
                rolePreferences.setFamilyBinding(famId, famName)
            } catch (e: Exception) {
                Log.w(TAG, "Unable to initialize parent family binding: ${e.message}")
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val notificationsStateFlow: StateFlow<FeedDataState> = familyBindingFlow
        .flatMapLatest { (familyId, _) ->
            if (familyId.isBlank()) {
                flowOf<FeedDataState>(FeedDataState.Loading)
            } else {
                repository.observeFamilyNotifications(familyId)
                    .map<List<NotificationRecord>, FeedDataState> { FeedDataState.Ready(it) }
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing notifications", rootError)
                        emit(FeedDataState.Error(rootError.message ?: "Failed to load notifications"))
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), FeedDataState.Loading)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val alertsFlow: StateFlow<List<ListenerAlert>> = familyBindingFlow
        .flatMapLatest { (familyId, _) ->
            if (familyId.isBlank()) {
                flowOf(emptyList())
            } else {
                repository.observeFamilyAlerts(familyId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val membersFlow: StateFlow<List<FamilyMember>> = familyBindingFlow
        .flatMapLatest { (familyId, _) ->
            if (familyId.isBlank()) {
                flowOf(emptyList())
            } else {
                repository.observeFamilyMembers(familyId)
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private val filterInputsFlow = combine(
        familyBindingFlow,
        selectedAppFilter,
        searchQuery
    ) { binding, appFilter, query ->
        Triple(binding, appFilter, query)
    }

    private val streamDataFlow = combine(
        notificationsStateFlow,
        alertsFlow,
        membersFlow
    ) { feedState, alerts, members ->
        Triple(feedState, alerts, members)
    }

    val uiState: StateFlow<ParentFeedUiState> = combine(
        filterInputsFlow,
        streamDataFlow
    ) { (binding, appFilter, query), (feedState, alerts, members) ->
        val (familyId, familyName) = binding

        val grouped = if (feedState is FeedDataState.Ready) {
            filterAndGroupByDay(feedState.records, appFilter, query)
        } else {
            emptyList()
        }

        ParentFeedUiState(
            familyId = familyId,
            familyName = familyName,
            selectedAppFilter = appFilter,
            searchQuery = query,
            feedState = feedState,
            groupedDays = grouped,
            unacknowledgedAlerts = alerts.filter { !it.acknowledged },
            childMembers = members.filter { it.role == "child" }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = ParentFeedUiState()
    )

    fun setAppFilter(app: MonitoredApp?) {
        selectedAppFilter.value = app
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun acknowledgeAlert(alertId: String) {
        val famId = uiState.value.familyId
        if (famId.isBlank() || alertId.isBlank()) return
        viewModelScope.launch {
            try {
                repository.acknowledgeAlert(famId, alertId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acknowledge alert: ${e.message}")
            }
        }
    }

    private fun filterAndGroupByDay(
        records: List<NotificationRecord>,
        appFilter: MonitoredApp?,
        query: String
    ): List<DayGroupedNotifications> {
        val trimmed = query.trim().lowercase()
        val filtered = records.filter { rec ->
            val matchesApp = appFilter == null || rec.packageName in appFilter.packages ||
                rec.appLabel.equals(appFilter.displayName, ignoreCase = true)
            val matchesSearch = trimmed.isEmpty() ||
                rec.title.lowercase().contains(trimmed) ||
                rec.text.lowercase().contains(trimmed) ||
                rec.bigText.lowercase().contains(trimmed) ||
                rec.conversationTitle.lowercase().contains(trimmed) ||
                rec.messages.any { it.lowercase().contains(trimmed) }
            matchesApp && matchesSearch
        }.sortedByDescending { it.postTimeMs }

        val today = LocalDate.now()
        val yesterday = today.minusDays(1)
        val prettyFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.getDefault())

        return filtered.groupBy { it.dayKey.ifBlank { today.toString() } }
            .entries
            .sortedByDescending { it.key }
            .map { (dayKey, items) ->
                val header = try {
                    val date = LocalDate.parse(dayKey)
                    when (date) {
                        today -> "Today"
                        yesterday -> "Yesterday"
                        else -> date.format(prettyFormatter)
                    }
                } catch (_: Exception) {
                    dayKey
                }
                DayGroupedNotifications(dayKey = dayKey, displayHeader = header, items = items)
            }
    }

    private companion object {
        const val TAG = "ParentFeedVM"
    }
}
