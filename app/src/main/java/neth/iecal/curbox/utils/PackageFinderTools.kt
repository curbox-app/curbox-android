package neth.iecal.curbox.utils

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.inputmethod.InputMethodManager

@Suppress("DEPRECATION")
fun AccessibilityService.isActivePackage(packageName: String): Boolean {
    val root = rootInActiveWindow ?: return false
    return try {
        root.packageName?.toString() == packageName
    } finally {
        root.recycle()
    }
}


fun getDefaultLauncherPackageName(packageManager: PackageManager): String? {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
    }

    val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolveInfo?.activityInfo?.packageName
}


fun getCurrentKeyboardPackageName(context: Context): String? {
    context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val currentInputMethodId = android.provider.Settings.Secure.getString(
        context.contentResolver,
        android.provider.Settings.Secure.DEFAULT_INPUT_METHOD
    )
    return currentInputMethodId?.substringBefore('/')
}
