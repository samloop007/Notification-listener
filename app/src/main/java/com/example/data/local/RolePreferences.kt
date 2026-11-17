package com.example.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class AppRole(val storageValue: String) {
    UNSET("unset"),
    CHILD("child"),
    PARENT("parent");

    companion object {
        fun fromStorage(value: String?): AppRole =
            entries.firstOrNull { it.storageValue == value } ?: UNSET
    }
}

data class PersistedAppState(
    val role: AppRole = AppRole.UNSET,
    val familyId: String = "",
    val familyName: String = "",
    val disclosureAccepted: Boolean = false,
    val companionAssociated: Boolean = false,
    val lastListenerHealthy: Boolean = false,
    val lastRevocationAlertAtMs: Long = 0L,
    val activeAuthUid: String = ""
)

val Context.kinEchoDataStore: DataStore<Preferences> by preferencesDataStore(name = "kinecho_prefs")

@Singleton
class RolePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.kinEchoDataStore

    private object Keys {
        val ROLE = stringPreferencesKey("role")
        val FAMILY_ID = stringPreferencesKey("family_id")
        val FAMILY_NAME = stringPreferencesKey("family_name")
        val DISCLOSURE_ACCEPTED = booleanPreferencesKey("disclosure_accepted")
        val COMPANION_ASSOCIATED = booleanPreferencesKey("companion_associated")
        val LAST_LISTENER_HEALTHY = booleanPreferencesKey("last_listener_healthy")
        val LAST_REVOCATION_ALERT_AT_MS = longPreferencesKey("last_revocation_alert_at_ms")
        val ACTIVE_AUTH_UID = stringPreferencesKey("active_auth_uid")
    }

    val stateFlow: Flow<PersistedAppState> = dataStore.data.map { prefs ->
        PersistedAppState(
            role = AppRole.fromStorage(prefs[Keys.ROLE]),
            familyId = prefs[Keys.FAMILY_ID].orEmpty(),
            familyName = prefs[Keys.FAMILY_NAME].orEmpty(),
            disclosureAccepted = prefs[Keys.DISCLOSURE_ACCEPTED] ?: false,
            companionAssociated = prefs[Keys.COMPANION_ASSOCIATED] ?: false,
            lastListenerHealthy = prefs[Keys.LAST_LISTENER_HEALTHY] ?: false,
            lastRevocationAlertAtMs = prefs[Keys.LAST_REVOCATION_ALERT_AT_MS] ?: 0L,
            activeAuthUid = prefs[Keys.ACTIVE_AUTH_UID].orEmpty()
        )
    }

    suspend fun current(): PersistedAppState = stateFlow.first()

    suspend fun setRole(role: AppRole) {
        dataStore.edit { it[Keys.ROLE] = role.storageValue }
    }

    suspend fun setFamilyBinding(familyId: String, familyName: String) {
        dataStore.edit {
            it[Keys.FAMILY_ID] = familyId
            it[Keys.FAMILY_NAME] = familyName
        }
    }

    suspend fun setDisclosureAccepted(accepted: Boolean) {
        dataStore.edit { it[Keys.DISCLOSURE_ACCEPTED] = accepted }
    }

    suspend fun setCompanionAssociated(associated: Boolean) {
        dataStore.edit { it[Keys.COMPANION_ASSOCIATED] = associated }
    }

    suspend fun setLastListenerHealthy(healthy: Boolean) {
        dataStore.edit { it[Keys.LAST_LISTENER_HEALTHY] = healthy }
    }

    suspend fun setLastRevocationAlertAtMs(timestampMs: Long) {
        dataStore.edit { it[Keys.LAST_REVOCATION_ALERT_AT_MS] = timestampMs }
    }

    suspend fun setActiveAuthUid(uid: String) {
        dataStore.edit { it[Keys.ACTIVE_AUTH_UID] = uid }
    }

    suspend fun clearFamilyAndRole() {
        dataStore.edit {
            it[Keys.ROLE] = AppRole.UNSET.storageValue
            it[Keys.FAMILY_ID] = ""
            it[Keys.FAMILY_NAME] = ""
            it[Keys.DISCLOSURE_ACCEPTED] = false
        }
    }
}
