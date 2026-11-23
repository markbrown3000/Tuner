// Tuner for Windows (and Mac/Linux): a separate build, so the Google TV app is never affected.
// It shares the TV app's playlist, guide, Plex and film code by copying those files in at build time.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "TunerDesktop"
