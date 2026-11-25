import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
    id("org.jetbrains.compose") version "1.7.3"
}

// "5.4.20" from the release tag (v5.4.20); Windows installers need three plain numbers.
val tunerVersion: String = (System.getenv("TUNER_VERSION_NAME") ?: "1.0.0")
    .split('.', '-').mapNotNull { it.toIntOrNull() }.plus(listOf(0, 0, 0)).take(3).joinToString(".")

// The TV app's code that has nothing Android-specific in it: playlists, guide, catch-up,
// channel merging, sport, languages, Plex, film info and recommendations.
val sharedFiles = listOf(
    "data/Models.kt", "data/M3uParser.kt", "data/XtreamClient.kt", "data/Http.kt", "data/XmltvParser.kt",
    "data/Catchup.kt", "data/ChannelMerge.kt", "data/Sports.kt", "data/WhatsOn.kt", "data/HomeFeed.kt",
    "data/Languages.kt", "data/FilmName.kt", "data/Taste.kt", "data/Recommend.kt", "data/SportFollow.kt",
    "data/Watchlist.kt", "integrations/Plex.kt", "integrations/Tmdb.kt", "integrations/YouTube.kt", "integrations/GoogleDrive.kt", "integrations/Espn.kt",
)

// Keys built in from GitHub's secrets (the same ones as the TV app): Google sign-in for sync,
// TMDB for film info, YouTube for search. Never in the code itself.
val generateKeys by tasks.registering {
    val out = layout.buildDirectory.file("generated/com/titanejstudios/tuner/desktop/Keys.kt")
    val values = listOf("TUNER_GOOGLE_CLIENT_ID", "TUNER_GOOGLE_CLIENT_SECRET", "TUNER_TMDB_KEY", "TUNER_YOUTUBE_KEY")
        .associateWith { (System.getenv(it) ?: "").trim() }
    inputs.property("keys", values.mapValues { it.value.hashCode() })
    inputs.property("version", tunerVersion)
    outputs.file(out)
    doLast {
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""
        out.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                "package com.titanejstudios.tuner.desktop\n\n" +
                    "/** Built in at build time from GitHub's secrets. */\n" +
                    "object Keys {\n" +
                    "    const val GOOGLE_CLIENT_ID = ${q(values.getValue("TUNER_GOOGLE_CLIENT_ID"))}\n" +
                    "    const val GOOGLE_CLIENT_SECRET = ${q(values.getValue("TUNER_GOOGLE_CLIENT_SECRET"))}\n" +
                    "    const val TMDB_KEY = ${q(values.getValue("TUNER_TMDB_KEY"))}\n" +
                    "    const val YOUTUBE_KEY = ${q(values.getValue("TUNER_YOUTUBE_KEY"))}\n" +
                    "    const val VERSION = ${q(tunerVersion)}\n" +
                    "}\n"
            )
        }
    }
}

val copyShared by tasks.registering(Copy::class) {
    from("../app/src/main/java/com/titanejstudios/tuner") { include(sharedFiles) }
    into(layout.buildDirectory.dir("shared/com/titanejstudios/tuner"))
}

kotlin {
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("shared"))
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated"))
}

tasks.named("compileKotlin") { dependsOn(copyShared, generateKeys) }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // The same JSON and XML readers Android has built in.
    implementation("org.json:json:20240303")
    implementation("net.sf.kxml:kxml2:2.3.0")
    // Video: VLC's engine (plays almost anything). Needs VLC installed on the PC.
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    // Finding the TV and phone on the Wi-Fi (the same "linked devices" as the TV app).
    implementation("org.jmdns:jmdns:3.5.9")
}

compose.desktop {
    application {
        mainClass = "com.titanejstudios.tuner.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Flicker"
            packageVersion = tunerVersion
            vendor = "Titan EJ Studios"
            description = "IPTV player"
            includeAllModules = true
            // VLC's engine (libvlc and its plugins), put in resources/windows/vlc by the GitHub build,
            // so nothing else needs installing. Without it, an installed VLC is used.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            windows {
                iconFile.set(project.file("icons/flicker.ico"))
                menuGroup = "Flicker"
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Keeps the same id so new versions install over old ones.
                upgradeUuid = "6b0f3c5e-4d4a-4b8e-9f3a-2f6d1a7c9e51"
            }
        }
    }
}
