package org.evergreenlabs.robin

object AppConfig {
    val aiEnabled: Boolean
        get() = BuildConfig.ENABLE_AI

    val versionName: String
        get() = BuildConfig.VERSION_NAME

    val versionCode: Int
        get() = BuildConfig.VERSION_CODE

    /**
     * When true, Settings shows debug-only chrome (performance benchmark).
     * Debug default on; set `robin.showDebugSettings=false` in local.properties
     * (or `ROBIN_SHOW_DEBUG_SETTINGS=false`) for release-looking screen recordings.
     * Always false in release builds.
     */
    val showDebugSettings: Boolean
        get() = BuildConfig.DEBUG && BuildConfig.SHOW_DEBUG_SETTINGS
}
