import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val keystoreProperties = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}

// Merely placing Google Services configuration beside the source does not opt in.
val enableAiSetting = (providers.gradleProperty("robin.enableAi").orNull
    ?: localProperties.getProperty("robin.enableAi") ?: "false").trim()
val enableAi = enableAiSetting.toBooleanStrictOrNull()
    ?: error("robin.enableAi must be exactly true or false")
if (enableAi) {
    check(file("google-services.json").isFile) {
        "AI is enabled: supply your own app/google-services.json and configure Firebase App Check."
    }
    apply(plugin = "com.google.gms.google-services")
}

// Debug Settings sheet chrome (benchmark / debug tests). Default on; set
// robin.showDebugSettings=false in local.properties for release-looking screen recordings.
val showDebugSettings: Boolean =
    (localProperties.getProperty("robin.showDebugSettings")
        ?: System.getenv("ROBIN_SHOW_DEBUG_SETTINGS")
        ?: "true")
        .trim()
        .equals("true", ignoreCase = true)

android {
    namespace = "org.evergreenlabs.robin"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.evergreenlabs.robin"
        minSdk = 33
        targetSdk = 36
        versionCode = 60
        versionName = "0.2.31-hardened"
        buildConfigField("boolean", "ENABLE_AI", enableAi.toString())
        // Release buildTypes override this to false below.
        buildConfigField("boolean", "SHOW_DEBUG_SETTINGS", "$showDebugSettings")
    }

    signingConfigs {
        create("release") {
            val storePath = keystoreProperties.getProperty("storeFile")
                ?: System.getenv("KEYSTORE_PATH")
            val storePass = keystoreProperties.getProperty("storePassword")
                ?: System.getenv("KEYSTORE_PASSWORD")
            val alias = keystoreProperties.getProperty("keyAlias")
                ?: System.getenv("KEY_ALIAS")
            val keyPass = keystoreProperties.getProperty("keyPassword")
                ?: System.getenv("KEY_PASSWORD")
            if (!storePath.isNullOrBlank() &&
                !storePass.isNullOrBlank() &&
                !alias.isNullOrBlank() &&
                !keyPass.isNullOrBlank()
            ) {
                storeFile = file(storePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Without the owner's keystore, produce an unsigned artifact for inspection.
            // Never silently sign Release with a development debug keystore.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never ship Settings debug chrome in release, even if local.properties enables it.
            buildConfigField("boolean", "SHOW_DEBUG_SETTINGS", "false")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.webkit:webkit:1.18.0")
    implementation("androidx.browser:browser:1.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation(platform("com.google.firebase:firebase-bom:34.16.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-appcheck")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")

    // These are tools used by lint, not app runtime dependencies. Keep their
    // transitive libraries above the reviewed security-fix versions.
    constraints {
        add("androidLintTool", "com.google.guava:guava:33.7.2-jre")
        add("androidLintTool", "org.apache.commons:commons-lang3:3.18.0")
        add("androidLintTool", "org.apache.httpcomponents:httpclient:4.5.14")
        add("androidLintTool", "org.bouncycastle:bcprov-jdk18on:1.86")
        add("androidLintTool", "org.bouncycastle:bcpkix-jdk18on:1.86")
        add("androidLintTool", "org.bouncycastle:bcutil-jdk18on:1.86")
    }
}

dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}
