/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.kiwifox

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Consumer
import org.json.JSONObject
import java.util.WeakHashMap
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps KiwiFox up to date from its GitHub repository. Each published build is a GitHub release tagged
 * `build-<number>` (tools/release.sh) carrying one APK per ABI; every APK knows its own number from
 * `assets/kiwifox/build.json` (written by build.sh). When the repository's latest release is newer, the
 * user is offered to download it (DownloadManager, with its own progress notification) and Android's
 * package installer opens on it. Releases are signed with the same key as the installed app, so they
 * install as updates and keep the profile.
 *
 * Checked when the browser opens, at most every [CHECK_INTERVAL_MS], and on demand (Settings, Themes screen).
 * A newer build is announced like a browser update: a notification, an "Update KiwiFox" item at the top of
 * the main menu and a "KiwiFox update" entry at the top of Settings, all starting the same download.
 */
internal object KiwiUpdater {
    const val REPOSITORY = "jdial1/kiwifox"
    private const val LATEST_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    private const val TIMEOUT_MS = 15_000
    private const val POLL_MS = 1000L
    private const val PREFS = "kiwifox_updater"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIPPED = "skipped_build"
    private const val TAG_PREFIX = "build-"
    private const val NOTES_LINES = 12
    private const val KEY_AVAILABLE = "available"
    private const val CHANNEL = "kiwifox_updates"
    private const val NOTIFICATION_ID = 0x4B1F
    const val ACTION_UPDATE = "org.mozilla.fenix.kiwifox.ACTION_UPDATE"
    const val SETTINGS_KEY = "kiwifox_update"

    /** This app's build: number (UTC yyyyMMddHHmm, 0 = unknown) and commit. */
    data class BuildInfo(val number: Long, val commit: String)

    /** A published release: its build number, the APK for this device, the notes and page. */
    data class Release(
        val number: Long,
        val apkUrl: String,
        val apkName: String,
        val notes: String,
        val page: String,
        val firefox: String = "",
    ) {
        fun toJson(): String = JSONObject()
            .put("number", number).put("apkUrl", apkUrl).put("apkName", apkName)
            .put("notes", notes).put("page", page).put("firefox", firefox).toString()

        /** "Build 202611200723 · Firefox 160.0" */
        val label: String get() = "Build $number" + if (firefox.isEmpty()) "" else " · Firefox $firefox"

        companion object {
            fun fromJson(text: String): Release? = runCatching {
                val j = JSONObject(text)
                Release(
                    j.getLong("number"), j.getString("apkUrl"), j.getString("apkName"),
                    j.optString("notes"), j.optString("page"), j.optString("firefox"),
                )
            }.getOrNull()
        }
    }

    @Volatile
    private var checking = false

    /** The newer release waiting to be installed, or null; Compose UI (the main menu) observes it. */
    var available: Release? by mutableStateOf(null)
        private set

    private var restored = false
    private val attached = WeakHashMap<Activity, Unit>()

    /**
     * Called for every browser activity on resume: restores a known update, starts the update when the
     * activity was opened from the update notification, and checks for a new build when due.
     */
    fun attach(activity: Activity) {
        restore(activity)
        if (!attached.containsKey(activity)) {
            attached[activity] = Unit
            if (activity.intent?.action == ACTION_UPDATE) {
                activity.intent.action = null
                startUpdate(activity)
            }
            (activity as? ComponentActivity)?.addOnNewIntentListener(
                Consumer { intent -> if (intent.action == ACTION_UPDATE) startUpdate(activity) },
            )
        }
        maybeCheck(activity)
    }

    private fun restore(context: Context) {
        if (restored) return
        restored = true
        val saved = prefs(context).getString(KEY_AVAILABLE, null)?.let(Release::fromJson)
        available = saved?.takeIf { it.number > current(context).number }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun remember(context: Context, release: Release?) {
        available = release
        prefs(context).edit().apply {
            if (release == null) remove(KEY_AVAILABLE) else putString(KEY_AVAILABLE, release.toJson())
        }.apply()
        if (release == null) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Installs the known update, or looks for one (reporting the result) when none is known. */
    fun startUpdate(activity: Activity) {
        val release = available
        if (release == null) check(activity, manual = true) else download(activity, release)
    }

    fun current(context: Context): BuildInfo = runCatching {
        val json = context.assets.open("kiwifox/build.json").bufferedReader().use { JSONObject(it.readText()) }
        BuildInfo(json.optLong("build", 0L), json.optString("commit"))
    }.getOrDefault(BuildInfo(0L, ""))

    /** "Build 202611111841 (e5ba7ed)", for the Themes screen. */
    fun describe(context: Context): String {
        val b = current(context)
        return if (b.number == 0L) "Local build (no build number)" else "Build ${b.number} (${b.commit.take(7)})"
    }

    /** Checks if the last check is older than [CHECK_INTERVAL_MS]; quiet unless an update is found. */
    fun maybeCheck(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_LAST_CHECK, 0L) < CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
        check(activity, manual = false)
    }

    /** Looks up the latest release; [manual] also reports "up to date" and errors, and ignores skips. */
    fun check(activity: Activity, manual: Boolean) {
        if (checking) return
        checking = true
        val app = activity.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val result = runCatching { latest() }
            checking = false
            main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                val release = result.getOrNull()
                val local = current(app)
                val skipped = prefs(app).getLong(KEY_SKIPPED, 0L)
                when {
                    release == null -> if (manual) {
                        toast(activity, "Couldn't check for updates: ${result.exceptionOrNull()?.message ?: "no release for this device"}")
                    }
                    release.number <= local.number -> {
                        remember(app, null)
                        if (manual) toast(activity, "KiwiFox is up to date (build ${local.number})")
                    }
                    else -> {
                        val announce = available?.number != release.number
                        remember(app, release)
                        if (manual) {
                            offer(activity, release, local)
                        } else if (announce && release.number != skipped) {
                            notify(app, release, local)
                        }
                    }
                }
            }
        }, "KiwiFox update check").start()
    }

    private fun latest(): Release? {
        val connection = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "KiwiFox-updater")
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) error("GitHub answered ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val number = json.optString("tag_name").removePrefix(TAG_PREFIX).toLongOrNull() ?: return null
            // Release titles end in "(Firefox <version>)" (tools/release.sh, the GitHub workflow).
            val firefox = Regex("\\(Firefox ([0-9.]+)\\)").find(json.optString("name"))?.groupValues?.get(1).orEmpty()
            val assets = json.optJSONArray("assets") ?: return null
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") }
            // The first ABI this device runs that has an APK.
            val apk = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { it.optString("name").contains(abi) }
            } ?: return null
            return Release(
                number = number,
                apkUrl = apk.getString("browser_download_url"),
                apkName = apk.getString("name"),
                notes = json.optString("body").lines().filter { it.isNotBlank() }.take(NOTES_LINES).joinToString("\n"),
                page = json.optString("html_url"),
                firefox = firefox,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun offer(activity: Activity, release: Release, local: BuildInfo) {
        val from = if (local.number == 0L) "a local build" else "build ${local.number}"
        AlertDialog.Builder(activity)
            .setTitle("KiwiFox update available")
            .setMessage("${release.label} is available (you have $from).\n\n${release.notes}")
            .setPositiveButton("Update") { _, _ -> download(activity, release) }
            .setNeutralButton("Skip this build") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SKIPPED, release.number).apply()
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /** A notification like an app store's update notice; tapping it (or Update) starts the update. */
    private fun notify(context: Context, release: Release, local: BuildInfo) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, "KiwiFox updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, org.mozilla.fenix.HomeActivity::class.java)
                .setAction(ACTION_UPDATE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val from = if (local.number == 0L) "a local build" else "build ${local.number}"
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(org.mozilla.fenix.R.drawable.ic_status_logo)
            .setContentTitle("KiwiFox update available")
            .setContentText("${release.label} (you have $from)")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${release.label} (you have $from)\n${release.notes}"))
            .setContentIntent(open)
            .addAction(0, "Update", open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun download(activity: Activity, release: Release) {
        val app = activity.applicationContext
        val dm = app.getSystemService(DownloadManager::class.java) ?: return
        val name = "kiwifox-${release.number}-${release.apkName.substringAfterLast('-')}"
        val id = runCatching {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(release.apkUrl))
                    .setTitle("KiwiFox build ${release.number}")
                    .setMimeType(APK_MIME)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name),
            )
        }.getOrElse {
            toast(activity, "Couldn't start the download: ${it.message}")
            return
        }
        toast(activity, "Downloading KiwiFox build ${release.number}…")
        val main = Handler(Looper.getMainLooper())
        // Poll the download (no broadcast receiver needed), then hand the APK to the package installer.
        Thread({
            while (true) {
                Thread.sleep(POLL_MS)
                val status = dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                    if (c.moveToFirst()) c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                } ?: DownloadManager.STATUS_FAILED
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    main.post { install(app, dm.getUriForDownloadedFile(id)) }
                    return@Thread
                }
                if (status == DownloadManager.STATUS_FAILED) {
                    main.post { toast(app, "KiwiFox update download failed") }
                    return@Thread
                }
            }
        }, "KiwiFox update download").start()
    }

    private fun install(context: Context, uri: Uri?) {
        if (uri == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            // First update: Android asks once to allow KiwiFox to install apps; the APK stays in Downloads.
            toast(context, "Allow KiwiFox to install updates, then open the downloaded APK from the notification")
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /** The activity behind a Compose / view context. */
    fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    private const val APK_MIME = "application/vnd.android.package-archive"
}
