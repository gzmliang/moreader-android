# Add project specific ProGuard rules here.
-keep class com.moreader.app.data.models.** { *; }
-keep class com.moreader.app.tts.** { *; }
-keep class com.moyue.app.localai.** { *; }
-keep class com.moyue.ai.model.** { *; }
-keep class com.moyue.app.data.models.** { *; }
-keep class com.moyue.app.tts.** { *; }
-keepclassmembers class com.moyue.app.localai.LlamaJniWrapper { native <methods>; }
-dontwarn okhttp3.**
-dontwarn org.jsoup.**

# PdfBox-Android 的可选依赖：JPEG2000(JPX) 图片解码器。
# 墨阅只用 PdfBox 提取文字层（页面渲染走系统 PdfRenderer），不需要该解码器。
-dontwarn com.gemalto.jp2.**
