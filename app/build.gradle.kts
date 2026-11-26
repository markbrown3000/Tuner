plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version: set here for builds on your PC. GitHub builds of a tag such as v5.4.1 use that instead
// (TUNER_VERSION_NAME). The version code is worked out from it (5.4.1 → 50401), so a newer version
// always installs over an older one, wherever it was built.
val tunerVersion: String = System.getenv("TUNER_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "5.4"

// Built-in TMDB key (film info), so there's nothing to type on the TV. It's kept in keys.properties
// (not uploaded to GitHub – see .gitignore) or, for GitHub builds, the TMDB_KEY secret.
val tmdbDefaultKey: String = System.getenv("TUNER_TMDB_KEY")?.takeIf { it.isNotBlank() }
    ?: rootProject.file("keys.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.trim().startsWith("tmdb=") }?.substringAfter("=")?.trim()
    ?: ""

/** Other built-in keys, from the environment (GitHub secrets) or keys.properties ("name=value"). */
fun builtInKey(env: String, name: String): String = System.getenv(env)?.takeIf { it.isNotBlank() }
    ?: rootProject.file("keys.properties").takeIf { it.exists() }?.readLines()
        ?.firstOrNull { it.trim().startsWith("$name=") }?.substringAfter("=")?.trim()
    ?: ""

// Google sign-in for "Sync between houses" (Google Drive): an OAuth client of the type
// "TVs and Limited Input devices". GitHub secrets GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.
val googleClientId: String = builtInKey("TUNER_GOOGLE_CLIENT_ID", "google_client_id")
val googleClientSecret: String = builtInKey("TUNER_GOOGLE_CLIENT_SECRET", "google_client_secret")
// YouTube Data API key (search). GitHub secret YOUTUBE_KEY.
val youtubeKey: String = builtInKey("TUNER_YOUTUBE_KEY", "youtube_key")
// OMDb (IMDb ratings) – the OMDB_KEY secret, or omdb_key=… in keys.properties.
val omdbKey: String = builtInKey("TUNER_OMDB_KEY", "omdb_key")
// Trakt (optional) – TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET secrets, from trakt.tv/oauth/applications.
val traktId: String = builtInKey("TUNER_TRAKT_ID", "trakt_id")
val traktSecret: String = builtInKey("TUNER_TRAKT_SECRET", "trakt_secret")

fun versionCodeFor(v: String): Int {
    val p = v.split('.', '-').mapNotNull { it.toIntOrNull() } + listOf(0, 0, 0)
    return p[0] * 10000 + p[1] * 100 + p[2]
}

android {
    namespace = "com.titanejstudios.tuner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.titanejstudios.tuner"
        minSdk = 24
        targetSdk = 35
        versionCode = versionCodeFor(tunerVersion)
        versionName = tunerVersion
        buildConfigField("String", "TMDB_KEY", "\"$tmdbDefaultKey\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
        buildConfigField("String", "GOOGLE_CLIENT_SECRET", "\"$googleClientSecret\"")
        buildConfigField("String", "YOUTUBE_KEY", "\"$youtubeKey\"")
        buildConfigField("String", "OMDB_KEY", "\"$omdbKey\"")
        buildConfigField("String", "TRAKT_ID", "\"$traktId\"")
        buildConfigField("String", "TRAKT_SECRET", "\"$traktSecret\"")
    }

    signingConfigs {
        // On GitHub, your PC's debug key is supplied as a secret (see .github/workflows/build.yml),
        // so GitHub-built updates install over the ones you built yourself.
        val ciKeystore = System.getenv("TUNER_KEYSTORE")
        if (!ciKeystore.isNullOrBlank()) {
            create("ci") {
                storeFile = file(ciKeystore)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // R8 optimisation makes Compose much faster on TV sticks – always install the release APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so the release APK can be sideloaded straight away
            // (and installs over the debug APK without uninstalling).
            signingConfig = signingConfigs.findByName("ci") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Unit tests run on the computer, not the TV: Android calls (like Log) just return defaults.
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    // FFmpeg audio decoding for sound the TV's own decoders can't play (MP2 on Freeview-style
    // channels, AC-3/E-AC-3/DTS on some sticks). Used only when the TV can't decode the track.
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.5.0+1")

    implementation("io.coil-kt:coil-compose:2.7.0")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.tvprovider:tvprovider:1.0.0")
    implementation("com.google.zxing:core:3.5.3")
    // Copying recordings to a network drive (SMB / Windows share / NAS).
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")
    // Built-in VPN (WireGuard – Surfshark, ProtonVPN, Mullvad… config files).
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    // YouTube's own embedded player (trailers and the YouTube section).
    // (13.x needs compileSdk 36; 12.1.2 with our own origin does the same job – see YouTubeScreens.)
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.2")

    // Tests of the pure logic (run with ./gradlew testDebugUnitTest, and on GitHub with every build).
    testImplementation("junit:junit:4.13.2")
    // The real org.json, because Android's copy only exists on the TV.
    testImplementation("org.json:json:20240303")
}
