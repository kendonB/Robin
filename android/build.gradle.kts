buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
    dependencies {
        // AGP's built-in Kotlin uses this patched stable Kotlin plugin version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
        // Build tools have their own classpath, separate from the app. These
        // constraints patch known transitive issues without shipping new SDKs.
        constraints {
            add("classpath", "com.google.guava:guava:33.7.2-jre")
            add("classpath", "org.apache.commons:commons-lang3:3.18.0")
            add("classpath", "org.apache.httpcomponents:httpclient:4.5.14")
            add("classpath", "org.bitbucket.b_c:jose4j:0.9.6")
            add("classpath", "org.bouncycastle:bcprov-jdk18on:1.86")
            add("classpath", "org.bouncycastle:bcpkix-jdk18on:1.86")
            add("classpath", "org.bouncycastle:bcutil-jdk18on:1.86")
            add("classpath", "org.jdom:jdom2:2.0.6.1")
        }
    }
}

plugins {
    // AGP supplies built-in Kotlin; Compose/serialization pin the patched compiler.
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
}

dependencyLocking {
    lockMode.set(LockMode.STRICT)
}
