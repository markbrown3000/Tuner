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
    "data/Football.kt", "integrations/Plex.kt", "integrations/Tmdb.kt",
)

val copyShared by tasks.registering(Copy::class) {
    from("../app/src/main/java/com/titanejstudios/tuner") { include(sharedFiles) }
    into(layout.buildDirectory.dir("shared/com/titanejstudios/tuner"))
}

kotlin {
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("shared"))
}

tasks.named("compileKotlin") { dependsOn(copyShared) }

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
}

compose.desktop {
    application {
        mainClass = "com.titanejstudios.tuner.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Tuner"
            packageVersion = tunerVersion
            vendor = "Titan EJ Studios"
            description = "IPTV player"
            includeAllModules = true
            windows {
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
