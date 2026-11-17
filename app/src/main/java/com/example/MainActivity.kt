package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.data.local.RolePreferences
import com.example.data.remote.FamilyRepository
import com.example.ui.navigation.KinEchoRoot
import com.example.ui.theme.KinEchoTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var rolePreferences: RolePreferences
    @Inject lateinit var repository: Lazy<FamilyRepository>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KinEchoTheme {
                KinEchoRoot(
                    rolePreferences = rolePreferences,
                    repositoryProvider = { repository.get() }
                )
            }
        }
    }
}
