# Tuner – R8 rules.
# Media3, Compose, Coil, WorkManager and TV Provider ship their own consumer rules.

# Keep line numbers so crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Background workers and receivers are created by name.
-keep class com.titanejstudios.tuner.tv.GuideRefreshWorker { <init>(...); }
-keep class com.titanejstudios.tuner.recording.** { *; }

# ZXing is only used for QR encoding.
-dontwarn com.google.zxing.**

# jcifs-ng (network drive): loads parts by name and references desktop-Java classes it never uses on Android.
-keep class jcifs.** { *; }
-dontwarn jcifs.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn javax.**
