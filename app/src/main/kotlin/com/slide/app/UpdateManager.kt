package com.slide.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Deliberately user-mediated updater for the public GitHub distribution channel. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String)

/** What [UpdateManager.downloadAndInstall] did, so the UI can say something true about it. */
enum class InstallOutcome {
    /** Android's package installer was handed the APK; it asks the user from here. */
    Handed,

    /** Permission to install packages was missing, so its settings page was opened instead. */
    NeedsPermission,
}

object UpdateManager {
    private const val RELEASES = "https://api.github.com/repos/Robertg761/Slide/releases?per_page=30"
    private const val TAG = "SlideUpdates"

    /** Slide ships two speech models, so its APK is large; leave room to write it and to spare. */
    private const val FREE_SPACE_HEADROOM = 64L * 1024 * 1024

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

    suspend fun downloadAndInstall(context: Context, update: UpdateInfo): InstallOutcome = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return@withContext InstallOutcome.NeedsPermission
        }
        require(update.apkUrl.startsWith("https://")) { "Update URL is not HTTPS" }

        val target = download(context, update)
        try {
            verify(context, target, update.version)
        } catch (e: Exception) {
            // A file that failed its checks must not be left behind: the next attempt would
            // otherwise be judged on the same bad bytes, and the whole point of retrying is to get
            // different ones.
            target.delete()
            throw e
        }

        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        InstallOutcome.Handed
    }

    /**
     * Fetches the release APK, and refuses to hand back anything it is not sure arrived whole.
     *
     * The download goes to a `.part` file that is only renamed once the byte count matches what the
     * server promised. A truncated APK is the failure mode that matters here: it is what a dropped
     * connection or a full cache leaves behind, it parses as nothing, and left under the real name
     * it makes every subsequent attempt fail the same way for a reason that has nothing to do with
     * the release.
     */
    private fun download(context: Context, update: UpdateInfo): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        // Only the download in progress belongs here; older versions are dead weight in a cache
        // directory the system is entitled to reclaim under pressure.
        directory.listFiles()?.forEach { it.delete() }

        val target = File(directory, "Slide-${update.version}.apk")
        val partial = File(directory, "Slide-${update.version}.apk.part")

        val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Slide-Android")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP $status for the release APK")
            }

            val expected = connection.contentLengthLong
            if (expected > 0 && directory.usableSpace < expected + FREE_SPACE_HEADROOM) {
                throw IOException(
                    "Not enough free space: the update needs about ${expected / (1024 * 1024)} MB",
                )
            }

            val written = connection.inputStream.use { input ->
                partial.outputStream().use { output -> input.copyTo(output) }
            }
            if (expected > 0 && written != expected) {
                throw IOException("Download stopped early at $written of $expected bytes")
            }
            if (!looksLikeZip(partial)) {
                throw IOException("The downloaded file is not an APK")
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        target.delete()
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not move the downloaded update into place")
        }
        return target
    }

    /** Every APK is a zip, and an error page served in its place is the thing this catches. */
    private fun looksLikeZip(file: File): Boolean {
        val magic = ByteArray(4)
        file.inputStream().use { if (it.read(magic) != magic.size) return false }
        return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
            magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
    }

    /**
     * Checks the downloaded APK is the Slide release it claims to be before offering it to Android.
     *
     * The signature comparison is the check that matters, and it is done here whenever the platform
     * will do it. But it is defence in depth, not the only line: Android refuses to install an
     * update signed by a different key regardless of what this code concludes, and the installer
     * asks the user by name either way.
     *
     * That is why a signing-certificate parse that comes back empty falls through to the plain
     * manifest read rather than aborting. Reading signing certificates out of a several-hundred-
     * megabyte archive inside an ordinary app process is the most failure-prone thing here, and
     * turning it into a hard stop means a perfectly good release is refused with a message about
     * the file being unreadable — which is both wrong and, since the same APK installs fine by
     * hand, actively misleading.
     */
    private fun verify(context: Context, apk: File, expectedVersion: String) {
        val pm = context.packageManager
        // Signing certificates are an API 28 concept. Below that Android's own check on install is
        // the whole of the guarantee, which is where it ends up on any device anyway.
        val canReadSigners = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        val signed = if (canReadSigners) {
            archiveInfo(pm, apk, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            null
        }
        val candidate = signed
            ?: archiveInfo(pm, apk, 0)
            ?: error("Android could not read the downloaded APK. Please try again.")

        require(candidate.packageName == context.packageName) {
            "The downloaded file is not Slide"
        }
        require(candidate.versionName == expectedVersion) {
            "Expected Slide $expectedVersion but the download is ${candidate.versionName}"
        }

        val installed = packageInfo(pm, context.packageName, 0)
        require(compare(candidate.versionName ?: "", installed.versionName ?: "") > 0) {
            "The downloaded release is not newer than the installed one"
        }

        if (signed != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireSameSigner(pm, context.packageName, signed)
        } else {
            Log.w(TAG, "Signing certificates unavailable; leaving the signature check to Android")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun requireSameSigner(pm: PackageManager, packageName: String, candidate: PackageInfo) {
        val installed = packageInfo(pm, packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val candidateSigners = candidate.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val installedSigners = installed.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        if (candidateSigners == null || installedSigners == null) {
            Log.w(TAG, "Signing certificates unreadable; leaving the signature check to Android")
            return
        }
        require(candidateSigners == installedSigners) { "Downloaded APK is not signed by Slide's release key" }
    }

    /**
     * [PackageManager.getPackageArchiveInfo] across API levels.
     *
     * The `PackageInfoFlags` overloads are API 33 and up. Calling them unconditionally on a minSdk
     * 26 app is a `NoSuchMethodError` on every older device, in the one code path nobody exercises
     * until an update is actually published.
     */
    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(apk.path, flags)
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Parsing the downloaded APK with flags $flags failed", e)
        null
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, packageName: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(packageName, flags)
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
