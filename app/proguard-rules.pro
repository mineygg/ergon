# Release builds are shrunk and obfuscated by R8. Nothing in the app uses reflection (JSON is read by hand,
# workers/receivers/activities are kept through the manifest), so only the libraries need a few notes.

# Readable crash traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Optional classes the libraries mention but that are not on Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.annotations.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (EncryptedSharedPreferences) reads its protobuf messages by field name.
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
    <fields>;
}
