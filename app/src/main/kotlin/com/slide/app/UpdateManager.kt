package com.slide.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Deliberately user-mediated updater for the public GitHub distribution channel. */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSha256: String,
    val apkSize: Long,
)

internal data class ReleaseCandidate(
    val update: UpdateInfo,
    val draft: Boolean,
    val prerelease: Boolean,
)

internal data class StagedDownloadTarget(
    val target: File,
    val partial: File,
    /** A previously verified download may still back an open Package Installer content URI. */
    val reuseExisting: Boolean,
)

/**
 * What [UpdateManager.install] did.
 *
 * Published rather than returned: an install outlives the screen that asked for it, so the thing
 * that reports it is not necessarily the thing that started it.
 */
sealed interface InstallOutcome {
    /** Android's package installer was handed the APK; it asks the user from here. */
    data object Handed : InstallOutcome

    /** Permission to install packages was missing, so its settings page was opened instead. */
    data object NeedsPermission : InstallOutcome

    /** A download was already running, so this call did nothing rather than fight it for the file. */
    data object AlreadyRunning : InstallOutcome

    /** The person canceled the in-flight download or left the settings activity. */
    data object Cancelled : InstallOutcome

    /** The download or its checks failed; [message] is what to tell the user. */
    data class Failed(val message: String?) : InstallOutcome
}

object UpdateManager {
    private const val RELEASES = "https://api.github.com/repos/Robertg761/Slide/releases?per_page=100"
    private const val RELEASES_HOST = "api.github.com"
    private const val RELEASES_PATH = "/repos/Robertg761/Slide/releases"
    private const val MAX_RELEASE_PAGES = 20
    private const val MAX_RELEASE_PAGE_CHARS = 8 * 1024 * 1024
    private const val MAX_RELEASE_NOTES_CHARS = 16 * 1024
    private const val MAX_RELEASE_URL_CHARS = 2 * 1024
    private const val TAG = "SlideUpdates"

    /** Granularity of published download progress. */
    private const val PROGRESS_PUBLISH_BYTES = 512L * 1024

    /** Leave enough room for the download plus Package Installer's separate staging copy. */
    private const val FREE_SPACE_HEADROOM = 64L * 1024 * 1024

    /** How long an APK the installer was handed, but which was never installed, is kept. */
    private const val STAGED_APK_GRACE_MS = 60L * 60L * 1000L

    /**
     * One download at a time, process-wide.
     *
     * Every download of a given release writes the same `.part` path. A second entry — which a
     * recreated activity produces for free — therefore does not merely waste bandwidth: it can
     * delete the first download's partial file mid-write and both then fail on byte counts that
     * have nothing to do with the release. Refusing the second caller makes partial cleanup safe;
     * completed APKs may still be owned by Package Installer and are preserved separately.
     */
    private val downloadInProgress = MutableStateFlow(false)

    /**
     * True for exactly as long as a download is running, and briefly while stale files are swept.
     *
     * The UI observes this rather than its own remembered flag so that a screen recreated in the
     * middle of a download cannot offer the button again.
     */
    val isDownloading: StateFlow<Boolean> = downloadInProgress.asStateFlow()

    /**
     * Bytes written by the running download, so a several-hundred-megabyte transfer reads as
     * moving rather than hung. Zeroed when a new download claims the slot.
     */
    private val downloadedBytesFlow = MutableStateFlow(0L)
    val downloadedBytes: StateFlow<Long> = downloadedBytesFlow.asStateFlow()

    /** Claims the single-flight slot, or reports that someone else holds it. */
    internal fun beginDownload(): Boolean {
        val claimed = downloadInProgress.compareAndSet(expect = false, update = true)
        if (claimed) downloadedBytesFlow.value = 0L
        return claimed
    }

    internal fun endDownload() {
        downloadInProgress.value = false
    }

    /**
     * Where a download runs.
     *
     * Not the caller's scope: a screen that rotates away in the middle of a 200 MB download should
     * not throw those bytes away, and its replacement must not start a second one. The work is
     * process-lifetime and its result is published, so whichever screen exists at the end reports
     * it. [SupervisorJob] keeps one failed install from taking the scope down with it.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val installJobLock = Any()
    private var activeInstallJob: Job? = null
    private var activeInstallCancellable = false

    private val lastOutcome = MutableStateFlow<InstallOutcome?>(null)

    /** The most recent finished install attempt, until a UI takes it with [consumeOutcome]. */
    val outcome: StateFlow<InstallOutcome?> = lastOutcome.asStateFlow()

    /** Marks the published [outcome] as reported, so it is not shown twice. */
    fun consumeOutcome() {
        lastOutcome.value = null
    }

    /**
     * Downloads, checks and hands over an update, outliving the caller.
     *
     * Returns immediately. Progress is [isDownloading] and the result is [outcome]; the single-
     * flight slot is claimed here, synchronously, so a UI that reads [isDownloading] straight after
     * this call already sees the download it just started.
     */
    fun install(context: Context, update: UpdateInfo) {
        val appContext = context.applicationContext
        if (!beginDownload()) {
            lastOutcome.value = InstallOutcome.AlreadyRunning
            return
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            lastOutcome.value = try {
                downloadAndInstall(appContext, update)
            } catch (t: Throwable) {
                // Throwable, not Exception: reading signing certificates out of a large APK is
                // an OutOfMemoryError away from failing, and that has to reach the user as a
                // failed update rather than as a dead process.
                if (t is CancellationException) throw t
                Log.w(TAG, "Update install failed", t)
                InstallOutcome.Failed(t.message)
            }
        }
        check(registerInstallJob(job)) { "download slot was claimed without an install job" }
        job.start()
    }

    /**
     * Cancels only the user-started install, never a short staging sweep.
     *
     * The job survives configuration changes, but [MainActivity] calls this when it actually
     * leaves the foreground. The download loop observes cancellation before every read and, most
     * importantly, immediately before launching Package Installer.
     */
    fun cancelInstall(): Boolean {
        val job = synchronized(installJobLock) {
            if (!activeInstallCancellable) return false
            activeInstallCancellable = false
            activeInstallJob
        } ?: return false
        job.cancel(CancellationException("Update canceled by user"))
        return true
    }

    /** Stops lifecycle cancellation once Slide intentionally hands control to a system activity. */
    internal fun markExternalHandoff(): Boolean = synchronized(installJobLock) {
        // This transition races cancelInstall(). Exactly one must win: after cancellation has
        // claimed the phase, a still-registered but cancelling Job must never launch an activity.
        if (activeInstallJob == null || !activeInstallCancellable) return false
        activeInstallCancellable = false
        true
    }

    /** Registers completion cleanup before a lazy job can start or be canceled. */
    internal fun registerInstallJob(job: Job): Boolean {
        synchronized(installJobLock) {
            if (activeInstallJob != null) return false
            activeInstallJob = job
            activeInstallCancellable = true
        }
        job.invokeOnCompletion { cause ->
            synchronized(installJobLock) {
                if (activeInstallJob === job) {
                    activeInstallJob = null
                    activeInstallCancellable = false
                }
            }
            if (cause is CancellationException) lastOutcome.value = InstallOutcome.Cancelled
            endDownload()
        }
        return true
    }

    /**
     * Deletes APKs left behind by finished, failed or abandoned installs.
     *
     * A staged APK in `filesDir` is permanent — the platform reclaims caches, not files — and it is
     * the largest thing this app will ever write. It cannot be deleted the moment the installer is
     * launched, because the installer reads the content URI only once the user confirms, so it is
     * swept at the start of the next update flow instead.
     */
    fun sweepStagedUpdates(context: Context) {
        val appContext = context.applicationContext
        scope.launch { sweepStagedUpdatesNow(appContext) }
    }

    private fun sweepStagedUpdatesNow(context: Context) = sweepStagingUnlessBusy(
        staging = stagingDirectory(context),
        // Releases up to 0.3.1 staged in the cache directory. Nothing reads that path any more, so
        // anything still sitting in it is pure litter.
        legacy = File(context.cacheDir, "updates"),
        installedVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull(),
    )

    /**
     * Sweeps both staging directories, unless a download owns them.
     *
     * Claiming the single-flight slot is what makes this safe: the sweep cannot run while a
     * download holds the directory, and a download cannot start while the sweep runs. Returns
     * whether it ran.
     */
    internal fun sweepStagingUnlessBusy(
        staging: File,
        legacy: File,
        installedVersion: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Boolean {
        if (!beginDownload()) return false
        try {
            staging.listFiles()?.forEach { file ->
                if (isStagedFileStale(file.name, file.lastModified(), nowMillis, installedVersion)) {
                    file.delete()
                }
            }
            legacy.listFiles()?.forEach { it.delete() }
            legacy.delete()
        } finally {
            endDownload()
        }
        return true
    }

    /**
     * Whether a file in the staging directory is safe to delete.
     *
     * "Delete everything" would be wrong by a hair: the package installer reads the APK through its
     * content URI when the user confirms, which can be well after this app was recreated by a
     * rotation and swept on the way back in. So an APK for a release newer than the installed one
     * is given an hour to be confirmed, while an APK whose version is already installed — the case
     * that matters, because that is what a *successful* update leaves behind — goes immediately.
     * A `.part` file is never read by anyone but the download that wrote it, and no download can be
     * running here, so it always goes.
     */
    internal fun isStagedFileStale(
        name: String,
        lastModifiedMillis: Long,
        nowMillis: Long,
        installedVersion: String?,
    ): Boolean {
        if (!name.endsWith(".apk")) return true
        val staged = name.removePrefix("Slide-").removeSuffix(".apk")
        val superseded = installedVersion != null &&
            isValidSemVer(staged) && isValidSemVer(installedVersion) &&
            compare(staged, installedVersion) <= 0
        return superseded || nowMillis - lastModifiedMillis >= STAGED_APK_GRACE_MS
    }

    private fun stagingDirectory(context: Context) = File(context.filesDir, "updates")

    suspend fun check(context: Context, includePrereleases: Boolean): UpdateInfo? = withContext(Dispatchers.IO) {
        // Reclaim what the last update flow staged before starting another one. Cheap, and it is
        // the path a user takes far more often than they take an install to completion.
        sweepStagedUpdatesNow(context)
        val current = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return@withContext null
        var bestCandidate: UpdateInfo? = null
        val seenPages = mutableSetOf<String>()
        var pageUrl: URL? = URL(RELEASES)
        while (pageUrl != null) {
            currentCoroutineContext().ensureActive()
            requireReleasePageAvailable(seenPages.size)
            if (!seenPages.add(pageUrl.toExternalForm())) {
                throw IOException("GitHub returned a release-pagination loop")
            }
            val connection = (pageUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000; readTimeout = 15_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Slide-Android")
            }
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    throw IOException("GitHub returned HTTP $status while checking for updates")
                }
                bestCandidate = selectReleasePage(
                    currentVersion = current,
                    includePrereleases = includePrereleases,
                    previousBest = bestCandidate,
                    candidates = parseReleaseCandidates(
                        readBoundedReleasePage(
                            input = connection.inputStream,
                            contentLength = connection.contentLengthLong,
                        ),
                    ),
                )
                currentCoroutineContext().ensureActive()
                pageUrl = nextReleasePage(connection.getHeaderField("Link"))
            } finally {
                connection.disconnect()
            }
        }
        bestCandidate
    }

    /** Reads one API page with cancellation points and a hard memory bound. */
    internal suspend fun readBoundedReleasePage(
        input: InputStream,
        contentLength: Long,
        maxChars: Int = MAX_RELEASE_PAGE_CHARS,
    ): String {
        require(maxChars > 0) { "release page limit must be positive" }
        if (contentLength > maxChars) {
            throw IOException("GitHub's release page was unexpectedly large")
        }
        return input.bufferedReader().use { reader ->
            val body = StringBuilder()
            val buffer = CharArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = reader.read(buffer)
                if (count < 0) break
                if (count > maxChars - body.length) {
                    throw IOException("GitHub's release page was unexpectedly large")
                }
                body.append(buffer, 0, count)
            }
            body.toString()
        }
    }

    /** Fail visibly instead of declaring the app current from a truncated release set. */
    internal fun requireReleasePageAvailable(alreadyFetched: Int) {
        if (alreadyFetched >= MAX_RELEASE_PAGES) {
            throw IOException("GitHub returned too many release pages")
        }
    }

    /**
     * Returns GitHub's next releases page, refusing to follow a pagination link off the expected
     * HTTPS API endpoint. Stable releases can otherwise disappear behind an arbitrary number of
     * newer prereleases, because filtering happens locally rather than on GitHub.
     */
    internal fun nextReleasePage(linkHeader: String?): URL? {
        val nextEntry = linkHeader
            ?.split(',')
            ?.firstOrNull { entry ->
                entry.substringAfter('>', missingDelimiterValue = "")
                    .split(';')
                    .any { it.trim() == "rel=\"next\"" }
            }
            ?: return null
        val rawUrl = nextEntry.substringAfter('<', missingDelimiterValue = "")
            .substringBefore('>', missingDelimiterValue = "")
        val next = runCatching { URL(rawUrl) }
            .getOrElse { throw IOException("GitHub returned a malformed release-pagination link", it) }
        if (next.protocol != "https" || next.host != RELEASES_HOST || next.path != RELEASES_PATH) {
            throw IOException("GitHub returned an unexpected release-pagination endpoint")
        }
        return next
    }

    internal fun selectReleaseCandidates(
        currentVersion: String,
        includePrereleases: Boolean,
        pages: Iterable<List<ReleaseCandidate>>,
    ): UpdateInfo? = pages.fold(null) { best, page ->
        selectReleasePage(currentVersion, includePrereleases, best, page)
    }

    /** Reduces each response page immediately so notes from earlier pages cannot accumulate. */
    internal fun selectReleasePage(
        currentVersion: String,
        includePrereleases: Boolean,
        previousBest: UpdateInfo?,
        candidates: List<ReleaseCandidate>,
    ): UpdateInfo? {
        if (!isValidSemVer(currentVersion)) return null
        return sequenceOf(previousBest)
            .filterNotNull()
            .plus(
                candidates.asSequence()
                    .filterNot { it.draft }
                    .filter { includePrereleases || !it.prerelease }
                    .map { it.update },
            )
            .filter { isValidSemVer(it.version) && compare(it.version, currentVersion) > 0 }
            .maxWithOrNull { left, right -> compare(left.version, right.version) }
    }

    private fun parseReleaseCandidates(json: String): List<ReleaseCandidate> =
        JSONArray(json).let { releases ->
            (0 until releases.length()).mapNotNull { i -> releases.getJSONObject(i) }
                .mapNotNull { release ->
                    val rawTag = release.getString("tag_name")
                    if (rawTag.length > 128) return@mapNotNull null
                    val version = rawTag.removePrefix("v")
                    if (!isValidSemVer(version)) return@mapNotNull null
                    val asset = release.getJSONArray("assets").let { assets ->
                        (0 until assets.length()).map { assets.getJSONObject(it) }
                            .firstOrNull { it.getString("name") == "Slide-$version.apk" }
                    } ?: return@mapNotNull null
                    val digest = asset.optString("digest").removePrefix("sha256:").lowercase()
                    if (!digest.matches(Regex("[0-9a-f]{64}"))) return@mapNotNull null
                    val size = asset.optLong("size", -1L)
                    if (size <= 0) return@mapNotNull null
                    val apkUrl = asset.getString("browser_download_url")
                    if (apkUrl.length > MAX_RELEASE_URL_CHARS || !apkUrl.startsWith("https://")) {
                        return@mapNotNull null
                    }
                    ReleaseCandidate(
                        update = UpdateInfo(
                            version = version,
                            notes = boundReleaseNotes(release.optString("body")),
                            apkUrl = apkUrl,
                            apkSha256 = digest,
                            apkSize = size,
                        ),
                        draft = release.optBoolean("draft"),
                        prerelease = release.optBoolean("prerelease") || isPrerelease(version),
                    )
                }
        }

    /** The body of [install]: runs under the single-flight slot its caller claimed. */
    private suspend fun downloadAndInstall(context: Context, update: UpdateInfo): InstallOutcome {
        check(downloadInProgress.value) { "downloadAndInstall() must hold the single-flight slot" }
        if (!context.packageManager.canRequestPackageInstalls()) {
            if (!markExternalHandoff()) {
                currentCoroutineContext().ensureActive()
                error("permission handoff lost its tracked install job")
            }
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return InstallOutcome.NeedsPermission
        }
        require(update.apkUrl.startsWith("https://")) { "Update URL is not HTTPS" }

        val staged = download(context, update)
        val target = staged.target
        try {
            verify(context, target, update.version)

            // The caller that asked for this update may be minutes gone. Handing the package
            // installer an APK after the work was canceled throws a full-screen system prompt over
            // whatever the user is doing now, which is the one thing a canceled download must not do.
            currentCoroutineContext().ensureActive()

            val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", target)
            if (!markExternalHandoff()) {
                currentCoroutineContext().ensureActive()
                error("installer handoff lost its tracked install job")
            }
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (t: Throwable) {
            // An APK that was never handed over will never be read by anyone: leaving it costs the
            // user a few hundred permanent megabytes. A file that failed its checks must go for a
            // second reason — the next attempt would otherwise be judged on the same bad bytes, and
            // the whole point of retrying is to get different ones.
            if (!staged.reuseExisting) target.delete()
            throw t
        }
        // Deliberately not deleted here: the installer opens the content URI only after the user
        // confirms, which is long after this returns. The next update flow sweeps it.
        return InstallOutcome.Handed
    }

    /**
     * Fetches the release APK, and refuses to hand back anything it is not sure arrived whole.
     *
     * The download goes to a `.part` file that is only renamed once the byte count matches what the
     * server promised. A truncated APK is the failure mode that matters here: it is what a dropped
     * connection or a full disk leaves behind, it parses as nothing, and left under the real name
     * it makes every subsequent attempt fail the same way for a reason that has nothing to do with
     * the release.
     *
     * Staging happens in `filesDir`, not `cacheDir`. `getAllocatableBytes` reports the space the
     * platform is willing to reclaim *by clearing caches* — this app's included — so a cached APK
     * can be deleted between handing it to the package installer and the user confirming the
     * install, on exactly the low-storage devices where that check mattered.
     */
    private suspend fun download(context: Context, update: UpdateInfo): StagedDownloadTarget {
        require(update.apkSize > 0) { "Update size is missing" }
        check(downloadInProgress.value) { "download() must hold the single-flight guard" }
        val installedVersion = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val staged = prepareDownloadTarget(
            directory = stagingDirectory(context),
            update = update,
            installedVersion = installedVersion,
        )
        if (staged.reuseExisting) return staged
        val directory = staged.target.parentFile ?: throw IOException("Update staging has no parent")
        val target = staged.target
        val partial = staged.partial

        val storage = context.getSystemService(StorageManager::class.java)
        // filesDir can live on adopted storage. Resolve the staging path's actual volume instead
        // of querying/allocating internal storage while writing somewhere else.
        val storageUuid = runCatching { storage.getUuidForPath(directory) }.getOrNull()
        val requiredBytes = requiredFreeBytes(update.apkSize)
        val allocatableBytes = storageUuid?.let { uuid ->
            runCatching { storage.getAllocatableBytes(uuid) }.getOrNull()
        } ?: directory.usableSpace
        if (allocatableBytes < requiredBytes) {
            throw IOException(
                "Not enough free space: downloading and staging this update needs about " +
                    "${requiredBytes / (1024 * 1024)} MB free",
            )
        }
        // Best effort: ask the platform to actually make the space it just said it could, so the
        // download is not racing other apps for it. A refusal here is not a reason to stop trying.
        storageUuid?.let { uuid ->
            runCatching { storage.allocateBytes(uuid, requiredBytes) }
        }

        val connection = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Slide-Android")
        }
        try {
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP $status for the release APK")
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > 0 && contentLength != update.apkSize) {
                throw IOException(
                    "GitHub reported $contentLength bytes for an ${update.apkSize}-byte release",
                )
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val written = connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    var publishedTotal = 0L
                    while (true) {
                        // The only suspension point in an otherwise straight-line blocking copy.
                        // Without it a canceled download runs to completion in the background; the
                        // throw unwinds through `use`, closing both streams, and the catch below
                        // deletes the partial file.
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count.toLong() > update.apkSize - total) {
                            throw IOException("The update exceeded its published ${update.apkSize}-byte size")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        total += count
                        // Published in coarse steps: every emission can recompose the dialog, and
                        // nobody needs progress at 8 KB granularity.
                        if (total - publishedTotal >= PROGRESS_PUBLISH_BYTES) {
                            publishedTotal = total
                            downloadedBytesFlow.value = total
                        }
                    }
                    downloadedBytesFlow.value = total
                    total
                }
            }
            if (written != update.apkSize) {
                throw IOException("Download stopped at $written of ${update.apkSize} published bytes")
            }
            if (!looksLikeZip(partial)) {
                throw IOException("The downloaded file is not an APK")
            }
            val actualDigest = digest.digest().toHex()
            if (actualDigest != update.apkSha256) {
                throw IOException("The downloaded APK failed its GitHub SHA-256 check")
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            connection.disconnect()
        }

        if (target.exists()) {
            partial.delete()
            throw IOException("A staged update became busy before the download completed")
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw IOException("Could not move the downloaded update into place")
        }
        return staged
    }

    /**
     * Preserves completed APKs that may still back Package Installer, while reclaiming files that
     * are provably stale. An identical target is reused and its grace period refreshed; a different
     * fresh target is never overwritten because an earlier installer may still hold its URI.
     */
    internal fun prepareDownloadTarget(
        directory: File,
        update: UpdateInfo,
        installedVersion: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): StagedDownloadTarget {
        require(update.apkSize > 0) { "Update size is missing" }
        require(isValidSemVer(update.version)) { "Update version is invalid" }
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Could not create update staging directory")
        }
        if (!directory.isDirectory) throw IOException("Update staging path is not a directory")

        val target = File(directory, "Slide-${update.version}.apk")
        val partial = File(directory, "Slide-${update.version}.apk.part")
        directory.listFiles()?.forEach { file ->
            if (isStagedFileStale(file.name, file.lastModified(), nowMillis, installedVersion) &&
                file.exists() && !file.delete()
            ) {
                throw IOException("Could not remove stale update ${file.name}")
            }
        }

        if (!target.exists()) {
            return StagedDownloadTarget(target, partial, reuseExisting = false)
        }
        if (!stagedApkMatches(target, update.apkSize, update.apkSha256)) {
            throw IOException("A recent staged update is still in use; retry later")
        }
        if (!target.setLastModified(nowMillis)) {
            throw IOException("Could not refresh the staged update's installer grace period")
        }
        return StagedDownloadTarget(target, partial, reuseExisting = true)
    }

    private fun stagedApkMatches(file: File, expectedSize: Long, expectedSha256: String): Boolean {
        if (file.length() != expectedSize || !looksLikeZip(file)) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex() == expectedSha256
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
        val candidateVersion = candidate.versionName ?: ""
        val installedVersion = installed.versionName ?: ""
        val candidateCode = PackageInfoCompat.getLongVersionCode(candidate)
        val installedCode = PackageInfoCompat.getLongVersionCode(installed)
        require(isValidUpgrade(candidateVersion, candidateCode, installedVersion, installedCode)) {
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
        val candidateHistory = candidate.signingInfo?.signingCertificateHistory
            ?.map { it.toCharsString() }
            ?.toSet()
        val installedSigners = installed.signingInfo?.apkContentsSigners
            ?.map { it.toCharsString() }
            ?.toSet()
        if (candidateHistory == null || installedSigners == null) {
            Log.w(TAG, "Signing certificates unreadable; leaving the signature check to Android")
            return
        }
        require(signerLineageAccepts(installedSigners, candidateHistory)) {
            "Downloaded APK is not signed by Slide's release key"
        }
    }

    /** Android accepts a proof-of-rotation update when its history contains every current signer. */
    internal fun signerLineageAccepts(
        installedSigners: Set<String>,
        candidateHistory: Set<String>,
    ): Boolean = installedSigners.isNotEmpty() && candidateHistory.containsAll(installedSigners)

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
        val a = requireNotNull(SemVer.parse(left)) { "Invalid semantic version: $left" }
        val b = requireNotNull(SemVer.parse(right)) { "Invalid semantic version: $right" }
        return a.compareTo(b)
    }

    internal fun isValidSemVer(value: String): Boolean = SemVer.parse(value) != null

    internal fun isPrerelease(value: String): Boolean = SemVer.parse(value)?.prerelease != null

    internal fun isValidUpgrade(
        candidateVersion: String,
        candidateCode: Long,
        installedVersion: String,
        installedCode: Long,
    ): Boolean = candidateCode > installedCode &&
        runCatching { compare(candidateVersion, installedVersion) > 0 }.getOrDefault(false)

    internal fun newest(currentVersion: String, candidates: List<UpdateInfo>): UpdateInfo? {
        if (!isValidSemVer(currentVersion)) return null
        return candidates
            .filter { isValidSemVer(it.version) && compare(it.version, currentVersion) > 0 }
            .maxWithOrNull { left, right -> compare(left.version, right.version) }
    }

    internal fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    internal fun requiredFreeBytes(downloadSize: Long): Long = try {
        Math.addExact(Math.multiplyExact(downloadSize, 2L), FREE_SPACE_HEADROOM)
    } catch (_: ArithmeticException) {
        Long.MAX_VALUE
    }

    /** Keeps update UI and saved instance state far below Android's Binder transaction limit. */
    internal fun boundReleaseNotes(
        notes: String,
        maxChars: Int = MAX_RELEASE_NOTES_CHARS,
    ): String {
        require(maxChars > 0) { "release notes limit must be positive" }
        if (notes.length <= maxChars) return notes
        var end = maxChars
        if (
            Character.isHighSurrogate(notes[end - 1]) &&
            end < notes.length &&
            Character.isLowSurrogate(notes[end])
        ) {
            end--
        }
        return notes.substring(0, end)
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        "%02x".format(byte.toInt() and 0xff)
    }

    private data class SemVer(
        val major: String,
        val minor: String,
        val patch: String,
        val prerelease: List<String>?,
    ) : Comparable<SemVer> {
        override fun compareTo(other: SemVer): Int {
            compareNumeric(major, other.major).takeIf { it != 0 }?.let { return it }
            compareNumeric(minor, other.minor).takeIf { it != 0 }?.let { return it }
            compareNumeric(patch, other.patch).takeIf { it != 0 }?.let { return it }

            val left = prerelease
            val right = other.prerelease
            if (left == null) return if (right == null) 0 else 1
            if (right == null) return -1

            for (index in 0 until minOf(left.size, right.size)) {
                val a = left[index]
                val b = right[index]
                val aNumber = a.all(Char::isDigit)
                val bNumber = b.all(Char::isDigit)
                val result = when {
                    aNumber && bNumber -> compareNumeric(a, b)
                    aNumber -> -1
                    bNumber -> 1
                    else -> a.compareTo(b)
                }
                if (result != 0) return result
            }
            return left.size.compareTo(right.size)
        }

        private fun compareNumeric(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)

        companion object {
            private val pattern = Regex(
                "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                    "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
            )

            fun parse(raw: String): SemVer? {
                if (raw.length > 128) return null
                val match = pattern.matchEntire(raw.removePrefix("v")) ?: return null
                val prerelease = match.groupValues[4]
                    .takeIf(String::isNotEmpty)
                    ?.split('.')
                if (prerelease?.any { identifier ->
                        identifier.length > 1 && identifier[0] == '0' &&
                            identifier.all(Char::isDigit)
                    } == true
                ) {
                    return null
                }
                return SemVer(
                    major = match.groupValues[1],
                    minor = match.groupValues[2],
                    patch = match.groupValues[3],
                    prerelease = prerelease,
                )
            }
        }
    }
}
