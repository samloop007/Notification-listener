package com.example.data.model

enum class MonitoredApp(
    val id: String,
    val displayName: String,
    val packages: Set<String>
) {
    WHATSAPP(
        id = "whatsapp",
        displayName = "WhatsApp",
        packages = setOf("com.whatsapp", "com.whatsapp.w4b")
    ),
    MESSAGES(
        id = "messages",
        displayName = "Messages",
        packages = setOf(
            "com.google.android.apps.messaging",
            "com.android.mms",
            "com.samsung.android.messaging"
        )
    ),
    MESSENGER(
        id = "messenger",
        displayName = "Messenger",
        packages = setOf("com.facebook.orca")
    ),
    TELEGRAM(
        id = "telegram",
        displayName = "Telegram",
        packages = setOf("org.telegram.messenger", "org.telegram.messenger.web")
    );

    companion object {
        val ALL_MONITORED_PACKAGES: Set<String> =
            entries.flatMap { it.packages }.toSet()

        fun fromPackage(packageName: String): MonitoredApp? =
            entries.firstOrNull { packageName in it.packages }

        fun displayLabelFor(packageName: String): String =
            fromPackage(packageName)?.displayName ?: packageName
    }
}
