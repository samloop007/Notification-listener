package com.example.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppRole
import com.example.data.local.RolePreferences
import com.example.data.model.GeneratedPairingCode
import com.example.data.remote.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PairingUiState {
    data object Idle : PairingUiState
    data object Loading : PairingUiState
    data class ParentCodeReady(val familyId: String, val familyName: String, val generated: GeneratedPairingCode) : PairingUiState
    data class ChildPaired(val familyId: String, val familyName: String) : PairingUiState
    data class Error(val message: String) : PairingUiState
}

@HiltViewModel
class PairingViewModel @Inject constructor(
    private val repository: FamilyRepository,
    private val rolePreferences: RolePreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow<PairingUiState>(PairingUiState.Idle)
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    fun generateParentCode(familyName: String = "My Family") {
        viewModelScope.launch {
            _uiState.value = PairingUiState.Loading
            try {
                val (familyId, resolvedName) = repository.ensureParentFamily(familyName)
                rolePreferences.setFamilyBinding(familyId, resolvedName)
                val code = repository.generatePairingCode(familyId)
                _uiState.value = PairingUiState.ParentCodeReady(familyId, resolvedName, code)
            } catch (e: Exception) {
                _uiState.value = PairingUiState.Error(formatCloudFunctionError(e))
            }
        }
    }

    fun redeemChildCode(code: String, childLabel: String) {
        val cleaned = code.trim().uppercase()
        if (cleaned.length != 6) {
            _uiState.value = PairingUiState.Error("Enter the 6-character pairing code from your parent device.")
            return
        }

        viewModelScope.launch {
            _uiState.value = PairingUiState.Loading
            try {
                val result = repository.redeemPairingCode(cleaned, childLabel)
                rolePreferences.setRole(AppRole.CHILD)
                rolePreferences.setFamilyBinding(result.familyId, result.familyName)
                _uiState.value = PairingUiState.ChildPaired(result.familyId, result.familyName)
            } catch (e: Exception) {
                _uiState.value = PairingUiState.Error(formatCloudFunctionError(e))
            }
        }
    }

    fun resetState() {
        _uiState.value = PairingUiState.Idle
    }

    private fun formatCloudFunctionError(e: Exception): String {
        val raw = e.localizedMessage ?: "Pairing request failed."
        return if (raw.contains("NOT_FOUND", ignoreCase = true) || raw.contains("UNAVAILABLE", ignoreCase = true)) {
            "$raw\n\nEnsure Firebase Cloud Functions (functions/src/index.ts) are deployed via `firebase deploy --only functions`."
        } else {
            raw
        }
    }
}
