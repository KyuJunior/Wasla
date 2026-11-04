# youtubedl-android ships no consumer rules. It maps yt-dlp's JSON into its own classes with Jackson
# (reflection) and loads helpers by name, so keep the library and Jackson whole.
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-dontwarn com.fasterxml.jackson.databind.**

# commons-compress/commons-io unpack the bundled Python and FFmpeg; some codecs are optional at runtime.
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.io.**
-dontwarn org.brotli.dec.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn java.beans.**
