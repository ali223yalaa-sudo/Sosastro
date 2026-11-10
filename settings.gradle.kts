pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://jitpack.io")
            content {
                includeModule("com.github.UnifiedPush", "android-connector")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        // Vendored copy of the one artifact JitPack failed to publish for the
        // pinned compose-richtext commit (see gradle/libs.versions.toml). Must be
        // listed before jitpack.io so the local copy wins for that coordinate.
        maven { url = uri("${rootDir}/gradle/local-maven") }
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master") }
    }
}

rootProject.name = "Amethyst"
include(":amethyst")
include(":nappletHost")
include(":benchmark")
include(":quartz")
include(":geode")
include(":commons")
include(":quic")
include(":nestsClient")
include(":desktopApp")
include(":cli")
include(":relayBench")
include(":quic-interop")
project(":quic-interop").projectDir = file("quic/interop")
