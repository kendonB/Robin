package org.evergreenlabs.robin.firebase

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import org.evergreenlabs.robin.BuildConfig

object FirebaseBootstrap {
    @Volatile
    var isConfigured: Boolean = false
        private set

    @Synchronized
    fun configure(context: Context): Boolean {
        if (!BuildConfig.ENABLE_AI) return false
        if (isConfigured) return true
        // Automatic Firebase providers are removed from the manifest. Only this
        // explicit opt-in initialises Firebase and installs the attestation provider.
        return try {
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.initializeApp(context)
                ?: return false
            AppCheckInstaller.install(FirebaseAppCheck.getInstance(app))
            isConfigured = true
            true
        } catch (_: Exception) {
            // Missing/invalid owner configuration must not crash the feed or expose SDK errors.
            isConfigured = false
            false
        }
    }
}
