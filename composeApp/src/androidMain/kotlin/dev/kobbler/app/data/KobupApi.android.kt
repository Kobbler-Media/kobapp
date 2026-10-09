package dev.kobbler.app.data

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import dev.kobbler.app.platform.settingsContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val KOBUP_PACKAGE = "com.kobup.app"
private const val PROVIDER_AUTHORITY = "com.kobup.app.provider"
private const val ACTION_OPEN_APP = "com.kobup.action.OPEN_APP"

actual object KobupUpdater {

    private fun isKobupInstalled(): Boolean {
        val ctx = settingsContext ?: return false
        return try {
            ctx.packageManager.getPackageInfo(KOBUP_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    actual suspend fun checkForUpdate(): AppUpdate? = withContext(Dispatchers.IO) {
        val ctx = settingsContext ?: return@withContext null
        if (!isKobupInstalled()) return@withContext null

        try {
            val uri = Uri.parse("content://$PROVIDER_AUTHORITY/update_status/${ctx.packageName}")
            val cursor = ctx.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val status = it.getString(it.getColumnIndexOrThrow("status"))
                    if (status == "available") {
                        val versionName = it.getString(it.getColumnIndexOrThrow("version_name"))
                        val versionCode = it.getLong(it.getColumnIndexOrThrow("version_code"))
                        AppUpdate(versionName = versionName, versionCode = versionCode)
                    } else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    actual fun openForUpdate() {
        val ctx = settingsContext ?: return
        if (!isKobupInstalled()) return

        try {
            val intent = Intent(ACTION_OPEN_APP).apply {
                setPackage(KOBUP_PACKAGE)
                putExtra("package_name", ctx.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {
            try {
                ctx.packageManager.getLaunchIntentForPackage(KOBUP_PACKAGE)?.let {
                    ctx.startActivity(it)
                }
            } catch (_: Exception) {}
        }
    }
}
