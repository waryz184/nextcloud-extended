# Google Tink / errorprone (dépendances transitives de security-crypto)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontnote okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Data models - keep field names for JSON/ICS parsing
-keepclassmembers class xyz.luna.nextcloudextended.data.model.** { *; }

# org.json
-keep class org.json.** { *; }

# Apache POI — keep ALL classes intact: POI uses Class.forName() and ServiceLoader
# internally to instantiate its own classes by original name; R8 renaming breaks this
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.openxmlformats.**
-dontwarn org.etsi.**
-dontwarn org.w3.x2000.**
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-keep class org.openxmlformats.** { *; }
-keep class com.microsoft.schemas.** { *; }
# POI transitive deps — AWT/desktop-only classes absent on Android
-dontwarn java.awt.**
-dontwarn com.graphbuilder.**
# POI uses log4j-api 2.x as its logging facade. It instantiates message/logger factories
# by name via reflection (e.g. DefaultFlowMessageFactory) during static init — keep the
# whole package intact so R8 doesn't rename/strip the reflectively-created classes.
-keep class org.apache.logging.log4j.** { *; }
# POI transitive deps (log4j2 optional integrations not present on Android)
-dontwarn aQute.bnd.annotation.**
-dontwarn org.osgi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.lmax.disruptor.**
-dontwarn com.conversantmedia.**
-dontwarn com.fasterxml.jackson.**

# StAX for Apache XMLBeans / POI OOXML on Android.
# Android has no javax.xml.stream at all — we bundle the API (stax-api) and impl (aalto-xml).
# Keep both intact: the factory-finder + ServiceLoader resolve these by name at runtime.
-keep class javax.xml.stream.** { *; }
-keep class com.fasterxml.aalto.** { *; }
-keep class org.codehaus.stax2.** { *; }
