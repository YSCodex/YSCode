# YSCode ProGuard / R8 Keep Rules

-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# kotlinx.serialization models & companion objects
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class com.example.**$$serializer { *; }
-keepclassmembers class com.example.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.example.llm.LlmContentPart** { *; }
-keep class com.example.llm.ModelConfig { *; }
-keep class com.example.mcp.McpServerConfig { *; }
-keep class com.example.data.** { *; }

# Navigation Compose type-safe routes
-keep class com.example.ui.AppRoute** { *; }

# Room entities & DAOs
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# OkHttp / Coroutines warnings suppression
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
