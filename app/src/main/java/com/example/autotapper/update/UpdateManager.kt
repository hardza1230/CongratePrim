package com.example.autotapper.update

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.autotapper.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Simple "check a web server on launch, then self-update" flow (option B).
 *
 * The server is just a JSON file published next to the APK on the GitHub
 * Release. On launch we fetch it, compare its versionCode with our own
 * BuildConfig.VERSION_CODE, and if the server is newer we offer to download and
 * install the new APK. Because every build is signed with the same key, the
 * download installs straight over the current app (no uninstall).
 */
object UpdateManager {

    private const val VERSION_URL =
        "https://github.com/hardza1230/CongratePrim/releases/download/latest-apk/version.json"

    private val main = Handler(Looper.getMainLooper())

    /** @param silent when true, stay quiet if already up to date / on errors. */
    fun checkForUpdate(activity: Activity, silent: Boolean = true) {
        Thread {
            try {
                val json = httpGetText(VERSION_URL)
                val o = JSONObject(json)
                val remoteCode = o.getInt("versionCode")
                val remoteName = o.optString("versionName", remoteCode.toString())
                val apkUrl = o.optString("apkUrl", "")
                when {
                    remoteCode > BuildConfig.VERSION_CODE && apkUrl.isNotEmpty() ->
                        main.post { promptUpdate(activity, remoteName, apkUrl) }
                    !silent ->
                        main.post { toast(activity, "เป็นเวอร์ชันล่าสุดแล้ว") }
                }
            } catch (e: Exception) {
                if (!silent) main.post { toast(activity, "ตรวจสอบอัปเดตไม่ได้") }
            }
        }.start()
    }

    private fun promptUpdate(activity: Activity, versionName: String, apkUrl: String) {
        if (activity.isFinishing) return
        AlertDialog.Builder(activity)
            .setTitle("มีเวอร์ชันใหม่")
            .setMessage("เวอร์ชัน $versionName พร้อมให้อัปเดต ต้องการดาวน์โหลดและติดตั้งเลยไหม?")
            .setPositiveButton("อัปเดต") { _, _ -> ensureCanInstallThenDownload(activity, apkUrl) }
            .setNegativeButton("ภายหลัง", null)
            .show()
    }

    private fun ensureCanInstallThenDownload(activity: Activity, apkUrl: String) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            toast(activity, "โปรดอนุญาต 'ติดตั้งแอปที่ไม่รู้จัก' ให้แอปนี้ก่อน แล้วกดอัปเดตอีกครั้ง")
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}")
                )
            )
            return
        }
        downloadAndInstall(activity, apkUrl)
    }

    private fun downloadAndInstall(activity: Activity, apkUrl: String) {
        toast(activity, "กำลังดาวน์โหลดอัปเดต…")
        Thread {
            try {
                val outFile = File(activity.getExternalFilesDir(null), "update.apk")
                httpDownload(apkUrl, outFile)
                main.post { installApk(activity, outFile) }
            } catch (e: Exception) {
                main.post { toast(activity, "ดาวน์โหลดอัปเดตไม่สำเร็จ") }
            }
        }.start()
    }

    private fun installApk(activity: Activity, file: File) {
        val uri = FileProvider.getUriForFile(
            activity, "${activity.packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        activity.startActivity(intent)
    }

    private fun httpGetText(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { return it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun httpDownload(urlStr: String, out: File) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000
            readTimeout = 60000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
    }

    private fun toast(activity: Activity, msg: String) =
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
}
