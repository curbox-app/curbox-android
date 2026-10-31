package neth.iecal.curbox.api

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

internal object ApiPackageIdentity {
    @Suppress("DEPRECATION")
    fun signingCertificates(context: Context, packageName: String): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.sorted()
    }

    /** Changes when the package is uninstalled and installed again, but not on updates. */
    fun installTime(context: Context, packageName: String): Long =
        context.packageManager.getPackageInfo(packageName, 0).firstInstallTime
}
