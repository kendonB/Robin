pluginManagement {
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.firebase(\\..*)?")
                includeGroupByRegex("com\\.google\\.gms(\\..*)?")
                includeGroupByRegex("com\\.google\\.testing\\.platform(\\..*)?")
            }
        }
        mavenCentral()
        gradlePluginPortal {
            content { includeGroupByRegex("org\\.jetbrains\\.kotlin(\\..*)?") }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroupByRegex("androidx(\\..*)?")
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.firebase(\\..*)?")
                includeGroupByRegex("com\\.google\\.gms(\\..*)?")
                includeGroupByRegex("com\\.google\\.testing\\.platform(\\..*)?")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "Robin"
include(":app")
