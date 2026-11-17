# KinEcho — Parental Notification-Mirroring App

KinEcho is a single-APK dual-role (**CHILD** / **PARENT**) Android application built with Kotlin, Jetpack Compose, Hilt, Room, WorkManager, `NotificationListenerService`, `CompanionDeviceManager`, and Firebase (Auth, Firestore, Cloud Functions, FCM).

**Strict Scope:** Notification content only from `[WhatsApp, Messages, Messenger, Telegram]`. No SMS APIs (`READ_SMS`), no GPS/location, and no remote device control.

---

## Project Structure

```text
.
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/
│       │   │   ├── KinEchoApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── companion/CompanionAssociationManager.kt
│       │   │   ├── data/
│       │   │   │   ├── local/
│       │   │   │   │   ├── AppDatabase.kt
│       │   │   │   │   ├── CapturedNotificationDao.kt
│       │   │   │   │   ├── CapturedNotificationEntity.kt
│       │   │   │   │   └── RolePreferences.kt
│       │   │   │   ├── model/
│       │   │   │   │   ├── FirestoreModels.kt
│       │   │   │   │   └── MonitoredApps.kt
│       │   │   │   └── remote/
│       │   │   │       ├── FamilyRepository.kt
│       │   │   │       └── FirestoreErrorHandler.kt
│       │   │   ├── di/AppModule.kt
│       │   │   ├── oem/
│       │   │   │   ├── BatteryOptimizationHelper.kt
│       │   │   │   └── OemAutostartHelper.kt
│       │   │   ├── service/
│       │   │   │   ├── BootReceiver.kt
│       │   │   │   ├── KinEchoMessagingService.kt
│       │   │   │   ├── ListenerHealthRegistry.kt
│       │   │   │   ├── NotifListener.kt
│       │   │   │   └── WatchdogService.kt
│       │   │   │   └── SyncWorker.kt
│       │   │   └── ui/
│       │   │       ├── auth/{AuthScreen.kt, GoogleAuthHelper.kt}
│       │   │       ├── child/{ChildDashboardScreen.kt, ChildViewModel.kt, OemAutostartScreen.kt}
│       │   │       ├── disclosure/ProminentDisclosureScreen.kt
│       │   │       ├── navigation/KinEchoNavHost.kt
│       │   │       ├── onboarding/RoleSelectionScreen.kt
│       │   │       ├── pairing/{ChildPairingScreen.kt, ParentPairingScreen.kt, PairingViewModel.kt}
│       │   │       ├── parent/{ParentFeedScreen.kt, ParentFeedViewModel.kt}
│       │   │       └── theme/{Color.kt, Theme.kt, Type.kt}
│       │   └── res/...
│       └── test/java/com/example/
│           ├── base/FirestoreEmulatorTestBase.kt
│           └── data/remote/FamilyRepositoryRuleTest.kt
├── functions/
│   ├── package.json
│   ├── tsconfig.json
│   └── src/index.ts
├── firebase-blueprint.json
├── firebase.json
├── firestore.rules
├── firestore.test.js
├── gradle/libs.versions.toml
└── README.md
```

---

## Android 15 (API 35) & Android 16 (API 36) Platform Behavior

1. **Android 15+ OTP / Sensitive Notification Redaction (`RECEIVE_SENSITIVE_NOTIFICATIONS`)**:
   - Starting on Android 15 (API 35), the system redacts notification bodies containing One-Time Passcodes (OTPs) for untrusted `NotificationListenerService` apps (`"Sensitive notification content hidden"`).
   - **Production Path**: Declare `<uses-permission android:name="android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS" />` in `AndroidManifest.xml` AND establish a `CompanionDeviceManager` association (`CompanionAssociationManager.kt`) using `AssociationRequest.Builder().setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH)` so the listener earns the trusted companion exemption.
   - **Development / ADB Testing**: On debug builds without a physical paired companion wearable, grant the AppOp explicitly via ADB (see below).

2. **Android 14+ / 15 `specialUse` Foreground Service (`FOREGROUND_SERVICE_SPECIAL_USE`)**:
   - `WatchdogService` declares `android:foregroundServiceType="specialUse"` AND the mandatory `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" ... />` in `AndroidManifest.xml`. Without that property, Android 14+ throws `MissingForegroundServiceTypeException` / `IllegalArgumentException` at `startForeground()`.
   - On Android 15+, background network requests outside a valid lifecycle throw `IOException` / `UnknownHostException`; `SyncWorker` runs inside WorkManager with `NetworkType.CONNECTED` and exponential backoff (`BackoffPolicy.EXPONENTIAL`).

3. **Android 16 (API 36) Companion Presence & Credential Manager Notes**:
   - On Android 16+, `CompanionDeviceManager.startObservingDevicePresence(String)` and `CompanionDeviceService.onDeviceAppeared()` are deprecated in favor of `ObservingDevicePresenceRequest` and `onDevicePresenceEvent(DevicePresenceEvent)`.
   - `GoogleAuthHelper` builds each `GetCredentialRequest` with **one credential option** per request (`GetSignInWithGoogleOption` for interactive sign-in, `GetGoogleIdOption` for silent sign-in) so the Credential Manager bottom sheet stays responsive on API 36.

---

## ADB Commands for Real-Device Development Builds

Replace `com.aistudio.kinecho.mpxqrz` if you customize `applicationId`:

```bash
# 1. Grant NotificationListenerService access without navigating Settings
adb shell cmd notification allow_listener com.aistudio.kinecho.mpxqrz/com.example.service.NotifListener

# 2. Android 15+ (API 35): Grant RECEIVE_SENSITIVE_NOTIFICATIONS AppOp for dev builds
#    (bypasses OTP redaction during testing without a physical Companion wearable)
adb shell cmd appops set com.aistudio.kinecho.mpxqrz RECEIVE_SENSITIVE_NOTIFICATIONS allow

# 3. Verify AppOps status
adb shell cmd appops get com.aistudio.kinecho.mpxqrz RECEIVE_SENSITIVE_NOTIFICATIONS

# 4. Whitelist from Doze battery optimizations during dev testing
adb shell dumpsys deviceidle whitelist +com.aistudio.kinecho.mpxqrz

# 5. Simulate notification listener unbind / inspect active listeners
adb shell dumpsys notification --noredact | grep -A 15 "Notification listeners"
```

---

## OEM Background & Autostart Caveats (Real Hardware)

- **Xiaomi / Redmi / POCO (MIUI / HyperOS)**: Kills `NotificationListenerService` and `specialUse` FGS aggressively on screen-off. Users must enable **Autostart**, set Battery Saver to **No restrictions**, and lock KinEcho in the Recents tray.
- **Samsung (One UI)**: Places infrequently opened apps into **Sleeping / Deep Sleeping Apps** after ~3 days, revoking background execution. Add KinEcho to **Never auto sleeping apps** and set Battery to **Unrestricted**.
- **Oppo / Realme (ColorOS / Realme UI)**: Blocks background service auto-launch by default. Enable **Allow auto-launch** and **Allow background activity** in App Management -> Battery usage.
- **Vivo / iQOO (Funtouch OS / OriginOS)**: Terminates listeners within minutes unless **Autostart** in iManager and **High background power consumption** are enabled.
- **Huawei / Honor (EMUI / MagicOS)**: PowerGenie stops background services unless **Settings -> Battery -> App launch** is switched from Automatic to **Manage manually** (all 3 toggles ON).
- **OnePlus (OxygenOS)**: On Oplus/ColorOS-merged builds (Android 13–15), enable **Allow background activity** and **Allow auto-launch**, and disable **Pause app activity if unused**.

`OemAutostartHelper.kt` + `OemAutostartScreen.kt` detect `Build.MANUFACTURER` at runtime and surface device-specific instructions + deep-link intents.

---

## Google Play Console Declarations

1. **Prominent Disclosure & Consent (User Data Policy)**:
   - Shown in `ProminentDisclosureScreen.kt` *before* launching `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS` (Child) or opening the Parent Feed.
   - Explicitly lists monitored packages (`WhatsApp`, `Messages`, `Messenger`, `Telegram`), storage in private `familyId`-scoped Firestore, and negative guarantees (no SMS APIs, no location, no remote control).

2. **Foreground Service (`specialUse`) Declaration**:
   - In Play Console -> App content -> Foreground service permissions, declare `specialUse` with justification:
     *"Maintains persistent parental notification-mirroring listener health on the child device, invokes `NotificationListenerService.requestRebind()` if unbound by OEM power managers, and alerts the linked parent if notification access is disabled."*
   - Provide a screen-recorded video showing the persistent notification and health monitoring flow.

3. **Data Safety Form**:
   - **Data collected**: App activity / messages (notification titles, body text, and `MessagingStyle` replies from user-authorized chat apps), Device or other IDs (Firebase Auth UID, device model, FCM token).
   - **Data sharing**: Shared strictly within the user's authenticated `familyId` group; never sold or shared with third-party advertisers.
   - **Encryption**: Encrypted in transit via TLS (Firestore & HTTPS Cloud Functions) and at rest.
   - **Deletion**: Users can revoke Notification Access at any time and request account/family deletion.

4. **Authentication & Backend Deployment**:
   - In-app Android authentication uses Google Sign-In via Jetpack `CredentialManager`. If your companion Next.js web dashboard also uses Email/Password Auth, enable **Email/Password** in Firebase Console -> Authentication -> Sign-in method.
   - Deploy rules and Cloud Functions:
     ```bash
     firebase deploy --only firestore:rules
     cd functions && npm install && npm run build && cd ..
     firebase deploy --only functions
     ```
