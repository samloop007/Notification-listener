package com.example.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.local.AppRole
import com.example.data.local.RolePreferences
import com.example.data.remote.FamilyRepository
import com.example.service.WatchdogService
import com.example.ui.auth.AuthScreen
import com.example.ui.auth.GoogleAuthHelper
import com.example.ui.child.ChildDashboardScreen
import com.example.ui.child.OemAutostartScreen
import com.example.ui.disclosure.ProminentDisclosureScreen
import com.example.ui.onboarding.RoleSelectionScreen
import com.example.ui.pairing.ChildPairingScreen
import com.example.ui.pairing.ParentPairingScreen
import com.example.ui.parent.ParentFeedScreen
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data object RouteRoleSelect
@Serializable data class RouteDisclosure(val roleName: String)
@Serializable data object RouteParentFeed
@Serializable data object RouteParentPairing
@Serializable data object RouteChildDashboard
@Serializable data object RouteChildPairing
@Serializable data object RouteOemAutostart

private fun FirebaseAuth.authStateFlow(): Flow<com.google.firebase.auth.FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth -> trySend(auth.currentUser) }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}.distinctUntilChanged { old, new -> old?.uid == new?.uid }

@Composable
fun KinEchoRoot(
    rolePreferences: RolePreferences,
    repositoryProvider: () -> FamilyRepository
) {
    val auth = remember { Firebase.auth }
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)

    val user = currentUser
    if (user == null) {
        // Strictly auth-gate all Firestore-backed screens and ViewModels until Google Sign-In completes
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            AuthenticatedShell(
                uid = user.uid,
                userEmail = user.email ?: "Signed-in User",
                rolePreferences = rolePreferences,
                repositoryProvider = repositoryProvider
            )
        }
    }
}

@Composable
private fun AuthenticatedShell(
    uid: String,
    userEmail: String,
    rolePreferences: RolePreferences,
    repositoryProvider: () -> FamilyRepository
) {
    val prefsState by rolePreferences.stateFlow.collectAsStateWithLifecycle(initialValue = null)
    val prefs = prefsState

    if (prefs == null) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(modifier = Modifier.fillMaxSize())
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val navController = rememberNavController()

    val startDestination: Any = remember(uid) {
        when {
            prefs.role == AppRole.UNSET -> RouteRoleSelect
            prefs.role == AppRole.PARENT && !prefs.disclosureAccepted -> RouteDisclosure(AppRole.PARENT.storageValue)
            prefs.role == AppRole.PARENT -> RouteParentFeed
            prefs.role == AppRole.CHILD && !prefs.disclosureAccepted -> RouteDisclosure(AppRole.CHILD.storageValue)
            else -> RouteChildDashboard
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable<RouteRoleSelect> {
            RoleSelectionScreen(
                userEmail = userEmail,
                onRoleChosen = { chosenRole ->
                    scope.launch {
                        rolePreferences.setRole(chosenRole)
                        rolePreferences.setActiveAuthUid(uid)
                        withContext(Dispatchers.IO) {
                            try {
                                repositoryProvider().syncUserProfile(chosenRole, prefs.familyId.ifBlank { null })
                            } catch (_: Exception) {
                            }
                        }
                        if (!prefs.disclosureAccepted) {
                            navController.navigate(RouteDisclosure(chosenRole.storageValue)) {
                                popUpTo(RouteRoleSelect) { inclusive = true }
                            }
                        } else if (chosenRole == AppRole.PARENT) {
                            navController.navigate(RouteParentFeed) {
                                popUpTo(RouteRoleSelect) { inclusive = true }
                            }
                        } else {
                            navController.navigate(RouteChildDashboard) {
                                popUpTo(RouteRoleSelect) { inclusive = true }
                            }
                        }
                    }
                },
                onSignOut = {
                    WatchdogService.stop(context)
                    GoogleAuthHelper.signOut(credentialManager, scope)
                }
            )
        }

        composable<RouteDisclosure> { entry ->
            val roleName = entry.arguments?.getString("roleName")
            val role = AppRole.fromStorage(roleName)
            ProminentDisclosureScreen(
                role = role,
                onAccept = {
                    scope.launch {
                        rolePreferences.setDisclosureAccepted(true)
                        if (role == AppRole.PARENT) {
                            navController.navigate(RouteParentFeed) {
                                popUpTo(RouteDisclosure(role.storageValue)) { inclusive = true }
                            }
                        } else {
                            navController.navigate(RouteChildDashboard) {
                                popUpTo(RouteDisclosure(role.storageValue)) { inclusive = true }
                            }
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    }
                },
                onDecline = {
                    scope.launch {
                        rolePreferences.setDisclosureAccepted(false)
                        rolePreferences.setRole(AppRole.UNSET)
                        navController.navigate(RouteRoleSelect) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<RouteParentFeed> {
            ParentFeedScreen(
                onNavigateToPairing = { navController.navigate(RouteParentPairing) },
                onSwitchRoleOrSignOut = {
                    scope.launch {
                        rolePreferences.clearFamilyAndRole()
                        navController.navigate(RouteRoleSelect) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<RouteParentPairing> {
            ParentPairingScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable<RouteChildDashboard> {
            ChildDashboardScreen(
                onNavigateToPairing = { navController.navigate(RouteChildPairing) },
                onNavigateToAutostart = { navController.navigate(RouteOemAutostart) },
                onNavigateToDisclosure = {
                    navController.navigate(RouteDisclosure(AppRole.CHILD.storageValue))
                },
                onSwitchRoleOrSignOut = {
                    WatchdogService.stop(context)
                    scope.launch {
                        rolePreferences.clearFamilyAndRole()
                        navController.navigate(RouteRoleSelect) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
            )
        }

        composable<RouteChildPairing> {
            ChildPairingScreen(
                onPaired = {
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable<RouteOemAutostart> {
            OemAutostartScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
