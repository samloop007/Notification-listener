package com.example.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ListenerHealthState(
    val permissionGranted: Boolean = false,
    val serviceBound: Boolean = false,
    val activeNotificationsOk: Boolean = false,
    val lastCheckAtMs: Long = 0L,
    val lastRebindAttemptAtMs: Long = 0L,
    val otpRedactionDetected: Boolean = false
)

object ListenerHealthRegistry {
    private val _state = MutableStateFlow(ListenerHealthState())
    val state: StateFlow<ListenerHealthState> = _state.asStateFlow()

    fun update(transform: (ListenerHealthState) -> ListenerHealthState) {
        _state.value = transform(_state.value)
    }
}
