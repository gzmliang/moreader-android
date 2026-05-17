# Add project specific ProGuard rules here.
-keep class com.moreader.app.data.models.** { *; }
-keep class com.moreader.app.tts.** { *; }
-keep class com.moyue.app.localai.** { *; }
-keepclassmembers class com.moyue.app.localai.LlamaJniWrapper { native <methods>; }
-dontwarn okhttp3.**
-dontwarn org.jsoup.**
