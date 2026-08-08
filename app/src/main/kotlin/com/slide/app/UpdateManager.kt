package com.slide.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Deliberately user-mediated updater for the public GitHub distribution channel. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

object UpdateManager {
    private const val RELEASES = "https://api.github.com/repos/Robertg761/Slide/releases?per_page=30"

    suspend fun check(context: Context, includeAlphas: Boolean): UpdateInfo? = withContext(Dispatchers.IO) {
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return@withContext null
        val connection = (URL(RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Slide-Android")
        }
        val json = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        JSONArray(json).let { releases ->
            (0 until releases.length()).mapNotNull { i -> releases.getJSONObject(i) }
                .firstNotNullOfOrNull { release ->
                    if (release.optBoolean("draft") || (!includeAlphas && release.optBoolean("prerelease"))) return@firstNotNullOfOrNull null
                    val version = release.getString("tag_name").removePrefix("v")
                    if (compare(version, current) <= 0) return@firstNotNullOfOrNull null
                    val asset = release.getJSONArray("assets").let { assets ->
                        (0 until assets.length()).map { assets.getJSONObject(it) }
                            .firstOrNull { it.getString("name").matches(Regex("Slide-[0-9].*\\.apk")) }
                    } ?: return@firstNotNullOfOrNull null
                    UpdateInfo(version, release.optString("body"), asset.getString("browser_download_url"))
                }
        }
    }

    suspend fun downloadAndInstall(context: Context, update: UpdateInfo) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext
        }
        require(update.apkUrl.startsWith("https://"))
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "Slide-${update.version}.apk")
        (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
            disconnect()
        }
        verify(context, target, update.version)
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    private fun verify(context: Context, apk: File, expectedVersion: String) {
        val pm = context.packageManager
        val candidate = pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            ?: error("Android could not read the downloaded APK")
        require(candidate.packageName == context.packageName && candidate.versionName == expectedVersion)
        val installed = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val candidateSigners = requireNotNull(candidate.signingInfo).apkContentsSigners.map { it.toCharsString() }.toSet()
        val installedSigners = requireNotNull(installed.signingInfo).apkContentsSigners.map { it.toCharsString() }.toSet()
        require(candidateSigners == installedSigners) { "Downloaded APK is not signed by Slide's release key" }
        require(compare(candidate.versionName ?: "", installed.versionName ?: "") > 0) { "Downloaded APK is not newer" }
    }

    internal fun compare(left: String, right: String): Int {
        fun parse(value: String): List<String> = value.removePrefix("v").split("-", limit = 2).flatMap { it.split('.') }
        val a = parse(left); val b = parse(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrNull(i) ?: "0"; val y = b.getOrNull(i) ?: "0"
            val n = x.toIntOrNull(); val m = y.toIntOrNull()
            val result = if (n != null && m != null) n.compareTo(m) else x.compareTo(y)
            if (result != 0) return result
        }
        return 0
    }
}
