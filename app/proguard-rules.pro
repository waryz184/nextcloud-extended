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

# Apache POI
-dontwarn org.apache.poi.**
-dontwarn org.apache.xmlbeans.**
-dontwarn com.microsoft.schemas.**
-dontwarn org.openxmlformats.**
-dontwarn org.etsi.**
-dontwarn org.w3.x2000.**
-keep class org.apache.xmlbeans.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
# POI transitive deps (log4j2 optional integrations not present on Android)
-dontwarn aQute.bnd.annotation.**
-dontwarn org.osgi.**
-dontwarn org.apache.logging.log4j.**
-dontwarn com.lmax.disruptor.**
-dontwarn com.conversantmedia.**
-dontwarn com.fasterxml.jackson.**

# StAX implementation for Apache XMLBeans / POI OOXML on Android
# aalto-xml registers itself via META-INF/services — keep the impl classes so R8 doesn't strip them
-keep class com.fasterxml.aalto.** { *; }
-keep class * implements javax.xml.stream.XMLInputFactory { *; }
-keep class * implements javax.xml.stream.XMLOutputFactory { *; }
-keep class * implements javax.xml.stream.XMLEventFactory { *; }
