# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class com.aliyun.allinone.** {
*;
}

-keep class com.aliyun.rts.network.** {
*;
}

-keep class com.aliyun.common.** {
*;
}

-keep class com.huawei.multimedia.alivc.** {
*;
}

-keep class com.alivc.rtc.** {
*;
}

-keep class com.alivc.component.** {
*;
}

-keep class org.webrtc.** {
*;
}

-keep class com.alibaba.ty.conv.** {
*;
}

-keep class com.tongyi.multimodal_dialog.MultiModalDialog {
    private static final *;
}

-dontwarn com.aliyun.aio.keep.API
-dontwarn com.aliyun.aio.keep.CalledByNative

-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.*
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE