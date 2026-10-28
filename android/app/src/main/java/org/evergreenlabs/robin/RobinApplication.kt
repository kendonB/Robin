package org.evergreenlabs.robin

import android.app.Application
import org.evergreenlabs.robin.firebase.FirebaseBootstrap

class RobinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        // FirebaseInitProvider is removed from the merged manifest. A normal
        // personal build must not initialise a cloud SDK merely by starting.
        if (BuildConfig.ENABLE_AI) {
            FirebaseBootstrap.configure(this)
        }
    }
}
