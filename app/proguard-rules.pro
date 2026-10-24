# R8 / ProGuard 规则（v1.66.0 起启用混淆与资源压缩）
#
# 本项目主代码零反射、无动态类加载，AndroidX 与 Material 的 consumer 规则由 AGP
# 自动带入，所以这里只留几条「出事时看得见」的兜底，不堆通用模板。

# 崩溃信息保留源文件名与行号：release 包里出 crash，没有行号的栈等于没给
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 自定义 View 从布局 XML 实例化，构造器签名不能被改（AGP 会为布局里的自定义 View
# 生成 keep 规则，这里再兜一层，双保险不值得省）
-keep class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# 枚举：valueOf / values 被系统与序列化路径反射调用，混淆后必崩
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable CREATOR（Alarm / 备份结构以后若要进 Intent 就直接用得上）
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
