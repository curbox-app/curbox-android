# Keep crash logs readable: line numbers survive and can be retraced with mapping.txt.
-keepattributes SourceFile,LineNumberTable

# Settings and API payloads are stored as JSON through Gson reflection. Field names are the
# storage format, and default constructors supply the defaults for fields missing in old JSON.
-keep class neth.iecal.curbox.data.models.** { *; }
-keep class neth.iecal.curbox.ui.views.Contributor { *; }
# Settings stores a Pair, which Gson writes as "first" and "second".
-keepclassmembers class kotlin.Pair { <fields>; }
-keepclassmembers class kotlin.Triple { <fields>; }

# MPAndroidChart animates through ObjectAnimator property names.
-keep class com.github.mikephil.charting.animation.ChartAnimator { *; }


# Debug logging is dead weight in release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
