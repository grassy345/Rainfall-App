# ── HiveMQ / Netty ─────────────────────────────────
# Prevent R8 from stripping reflection-based classes used by HiveMQ's MQTT client
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-keep class com.hivemq.** { *; }
-dontwarn com.hivemq.**

# Netty tries to use classes that may not exist on Android
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.openjsse.**
-dontwarn reactor.blockhound.**
-dontwarn java.lang.management.**

# ── Room ───────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.**
