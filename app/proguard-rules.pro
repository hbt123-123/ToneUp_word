# Keep Room generated implementations (they are referenced reflectively by name).
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# --- Retrofit + OkHttp (gateway network stack) -------------------------------
# Retrofit resolves service interfaces via reflection: keep generic signatures and
# runtime annotations. kotlinx.serialization keeps itself (kotlinx-serialization-json
# ships its own consumer rules) — do NOT duplicate them here.
-keepattributes Signature, InnerClasses, EnclosingMethod, Annotations
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowoptimization class retrofit2.Response
# OkHttp's optional platform providers are absent on Android; silence their warnings.
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
