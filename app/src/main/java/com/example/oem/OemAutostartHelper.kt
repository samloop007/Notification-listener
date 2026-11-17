package com.example.oem

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

data class OemGuidance(
    val manufacturerName: String,
    val osUiName: String,
    val riskLevel: String,
    val summary: String,
    val steps: List<String>,
    val deepLinkIntents: List<Intent>
)

object OemAutostartHelper {

    fun getGuidanceForCurrentDevice(context: Context): OemGuidance {
        val mfg = Build.MANUFACTURER.orEmpty().trim()
        val lower = mfg.lowercase()

        return when {
            lower in setOf("xiaomi", "redmi", "poco") -> OemGuidance(
                manufacturerName = mfg,
                osUiName = "MIUI / HyperOS",
                riskLevel = "High — aggressive background kill",
                summary = "Xiaomi MIUI/HyperOS kills NotificationListenerService and foreground services on screen-off unless Autostart and No Restrictions are enabled.",
                steps = listOf(
                    "Open Settings -> Apps -> Manage apps -> KinEcho -> Autostart and enable it.",
                    "Open Battery saver -> select 'No restrictions'.",
                    "Lock KinEcho in the Recents tray (pull down on the app card -> tap Lock).",
                    "In Security -> Permissions -> Autostart, ensure KinEcho is toggled ON."
                ),
                deepLinkIntents = listOf(
                    componentIntent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                    componentIntent("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                        .putExtra("package_name", context.packageName)
                        .putExtra("package_label", "KinEcho"),
                    appDetailsIntent(context)
                )
            )

            lower == "samsung" -> OemGuidance(
                manufacturerName = "Samsung",
                osUiName = "One UI",
                riskLevel = "Medium-High — Deep Sleeping Apps",
                summary = "Samsung One UI puts apps into Sleeping/Deep Sleeping mode after ~3 days if not added to 'Never auto sleeping apps'.",
                steps = listOf(
                    "Open Settings -> Battery -> Background usage limits.",
                    "Open 'Never auto sleeping apps' -> tap + -> add KinEcho.",
                    "Open Settings -> Apps -> KinEcho -> Battery -> select 'Unrestricted'.",
                    "Disable 'Remove permissions if app is unused'."
                ),
                deepLinkIntents = listOf(
                    componentIntent("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    componentIntent("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                    appDetailsIntent(context)
                )
            )

            lower in setOf("oppo", "realme") -> OemGuidance(
                manufacturerName = mfg,
                osUiName = "ColorOS / Realme UI",
                riskLevel = "High — Auto-launch blocked by default",
                summary = "ColorOS blocks background service restarts and unbinds NotificationListenerService unless Auto-launch and Allow background activity are enabled.",
                steps = listOf(
                    "Open Settings -> Apps -> App management -> KinEcho -> Battery usage.",
                    "Enable 'Allow background activity' and 'Allow auto-launch'.",
                    "In Phone Manager -> Privacy permissions -> Startup manager, toggle KinEcho ON."
                ),
                deepLinkIntents = listOf(
                    componentIntent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                    componentIntent("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                    componentIntent("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity"),
                    appDetailsIntent(context)
                )
            )

            lower in setOf("vivo", "iqoo") -> OemGuidance(
                manufacturerName = mfg,
                osUiName = "Funtouch OS / OriginOS",
                riskLevel = "High — Background power kill",
                summary = "Vivo kills background services within minutes of screen off unless High Background Power Consumption and Autostart are turned on.",
                steps = listOf(
                    "Open iManager -> App manager -> Autostart -> enable KinEcho.",
                    "Open Settings -> Battery -> Background power consumption -> KinEcho -> select 'High background power consumption'.",
                    "Lock KinEcho in the Recent Apps switcher."
                ),
                deepLinkIntents = listOf(
                    componentIntent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                    componentIntent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                    appDetailsIntent(context)
                )
            )

            lower in setOf("huawei", "honor") -> OemGuidance(
                manufacturerName = mfg,
                osUiName = "EMUI / MagicOS",
                riskLevel = "High — PowerGenie",
                summary = "Huawei/Honor PowerGenie terminates background listeners unless App Launch is switched from Automatic to Manual.",
                steps = listOf(
                    "Open Settings -> Battery -> App launch.",
                    "Find KinEcho and toggle OFF 'Manage automatically'.",
                    "Enable all three manual toggles: Auto-launch, Secondary launch, and Run in background."
                ),
                deepLinkIntents = listOf(
                    componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                    componentIntent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
                    appDetailsIntent(context)
                )
            )

            lower == "oneplus" -> OemGuidance(
                manufacturerName = "OnePlus",
                osUiName = "OxygenOS",
                riskLevel = "Medium-High — App Auto-launch & Oplus Doze",
                summary = "OxygenOS (especially Android 13–15 ColorOS codebase) restricts background activity unless set to 'Allow background activity' and Auto-launch.",
                steps = listOf(
                    "Open Settings -> Apps -> App management -> KinEcho -> Battery usage.",
                    "Enable 'Allow foreground activity', 'Allow background activity', and 'Allow auto-launch'.",
                    "Turn OFF 'Pause app activity if unused'."
                ),
                deepLinkIntents = listOf(
                    componentIntent("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity"),
                    componentIntent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                    appDetailsIntent(context)
                )
            )

            else -> OemGuidance(
                manufacturerName = mfg.ifBlank { "Android" },
                osUiName = "Android ${Build.VERSION.RELEASE}",
                riskLevel = "Standard — Doze & App Standby",
                summary = "Standard Android Doze and Adaptive Battery can defer WorkManager syncs unless KinEcho is set to Unrestricted battery usage.",
                steps = listOf(
                    "Open Settings -> Apps -> KinEcho -> App battery usage.",
                    "Select 'Unrestricted' (not Optimized or Restricted).",
                    "Ensure 'Pause app activity if unused' is toggled OFF."
                ),
                deepLinkIntents = listOf(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    appDetailsIntent(context)
                )
            )
        }
    }

    fun launchBestAutostartSettings(context: Context, guidance: OemGuidance): Boolean {
        val pm = context.packageManager
        for (intent in guidance.deepLinkIntents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pm.resolveActivity(intent, 0) != null) {
                return try {
                    context.startActivity(intent)
                    true
                } catch (_: Exception) {
                    continue
                }
            }
        }
        return false
    }

    private fun componentIntent(pkg: String, cls: String): Intent =
        Intent().setComponent(ComponentName(pkg, cls))

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
}
