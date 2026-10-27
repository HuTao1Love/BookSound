package com.zyagodin.booksound.update

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.BuildConfigInfo
import com.zyagodin.booksound.core.update.AppVersion
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.core.wear.WatchResult
import com.zyagodin.booksound.shared.ApkInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A GitHub release with its phone and watch APKs. */
data class Release(val version: AppVersion, val phoneApk: Asset?, val watchApk: Asset?) {
    data class Asset(val url: String, val size: Long)
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState

    /**
     * What the latest release would change. [watchVersion] is null when no watch with BookSound
     * has told its version; [watchUpdate] is false then too.
     */
    data class Checked(
        val release: Release,
        val phoneVersion: String,
        val phoneUpdate: Boolean,
        val watchVersion: String?,
        val watchUpdate: Boolean,
    ) : UpdateState {
        val upToDate: Boolean get() = !phoneUpdate && !watchUpdate
    }

    data class Working(val step: Step, val progress: Float?) : UpdateState
    enum class Step { DOWNLOADING_WATCH, SENDING_WATCH, DOWNLOADING_PHONE }

    /**
     * The watch has the APK and asks to install it; the phone's installer was opened if
     * [phoneInstalling]. [watchNext]: the watch app goes out once the new phone app starts.
     */
    data class Done(val watchSent: Boolean, val phoneInstalling: Boolean, val watchNext: Boolean = false) : UpdateState

    data class Failed(val reason: Reason) : UpdateState
    enum class Reason { NETWORK, NO_RELEASE, WATCH_UNREACHABLE, WATCH_NO_SPACE, WATCH_FAILED, INSTALL }
}

/**
 * Checks GitHub for a newer BookSound release and installs it: the phone app first, then the
 * watch app (the phone sends it over the Data Layer; the watch asks to install). Installing the
 * phone app ends this process, so both APKs are downloaded before it and the watch's waits in
 * the cache; the updated app sends it when it starts ([resumeWatchUpdate]).
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    http: OkHttpClient,
) {
    private val api = http
    /** Downloads of tens of megabytes: no overall time limit, only for stalls. */
    private val downloads = http.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val data = Wearable.getDataClient(context)
    private val capabilities = Wearable.getCapabilityClient(context)
    private val channels = Wearable.getChannelClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val dir = File(context.cacheDir, "updates")
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null

    /** The user hid the running job's dialog: its progress and success stay quiet, a failure is still told. */
    @Volatile private var hidden = false
    /** What the running job last told, shown again when the user asks while it is hidden. */
    @Volatile private var last: UpdateState = UpdateState.Idle

    private fun publish(state: UpdateState) {
        last = state
        if (!hidden || state is UpdateState.Failed) _state.value = state
    }

    fun check() {
        if (job?.isActive == true) {
            hidden = false
            _state.value = last
            return
        }
        hidden = false
        job = scope.launch(Dispatchers.IO) {
            publish(UpdateState.Checking)
            val result = try {
                val release = latestRelease() ?: return@launch publish(UpdateState.Failed(UpdateState.Reason.NO_RELEASE))
                val phone = BuildConfigInfo.versionName(context)
                val watch = watchVersion()
                UpdateState.Checked(
                    release = release,
                    phoneVersion = phone,
                    phoneUpdate = release.phoneApk != null && isOlder(phone, release.version),
                    watchVersion = watch,
                    watchUpdate = watch != null && release.watchApk != null && isOlder(watch, release.version),
                )
            } catch (e: IOException) {
                Log.w(TAG, "Update check failed", e)
                UpdateState.Failed(UpdateState.Reason.NETWORK)
            } catch (e: org.json.JSONException) {
                // Not GitHub's JSON: a captive portal or a proxy's page answered.
                Log.w(TAG, "Update check failed", e)
                UpdateState.Failed(UpdateState.Reason.NETWORK)
            }
            publish(result)
        }
    }

    /** Installs what [checked] found outdated. */
    fun update(checked: UpdateState.Checked) {
        if (job?.isActive == true) return
        hidden = false
        job = scope.launch(Dispatchers.IO) {
            val version = checked.release.version.toString()
            val failure = try {
                val watchApk = if (checked.watchUpdate) {
                    download(checked.release.watchApk!!, watchApkName(version), UpdateState.Step.DOWNLOADING_WATCH)
                } else null
                if (checked.phoneUpdate) {
                    val apk = download(checked.release.phoneApk!!, "BookSound-$version.apk", UpdateState.Step.DOWNLOADING_PHONE)
                    if (ApkInstaller.versionOf(context, apk) == null) throw IOException("Not a BookSound APK")
                    if (watchApk != null) prefs.edit().putString(KEY_PENDING_WATCH, version).apply()
                    publish(UpdateState.Done(watchSent = false, phoneInstalling = true, watchNext = watchApk != null))
                    withContext(Dispatchers.Main) { ApkInstaller.install(context, apk) }
                    null
                } else if (watchApk != null) {
                    sendToWatch(watchApk, version) ?: run {
                        publish(UpdateState.Done(watchSent = true, phoneInstalling = false))
                        null
                    }
                } else {
                    publish(UpdateState.Done(watchSent = false, phoneInstalling = false))
                    null
                }
            } catch (e: IOException) {
                Log.w(TAG, "Update failed", e)
                UpdateState.Reason.NETWORK
            } catch (e: Exception) {
                Log.w(TAG, "Update failed", e)
                UpdateState.Reason.INSTALL
            }
            failure?.let { publish(UpdateState.Failed(it)) }
        }
    }

    /**
     * After the phone app was updated: sends the watch app downloaded alongside it. Does nothing
     * until the phone runs that version (the user may not have confirmed the installation yet).
     * A watch that isn't connected now gets it on a later start; meanwhile Settings offers it too.
     */
    fun resumeWatchUpdate() {
        val pending = prefs.getString(KEY_PENDING_WATCH, null) ?: return
        val pendingVersion = AppVersion.parse(pending)
        val phone = AppVersion.parse(BuildConfigInfo.versionName(context))
        if (pendingVersion == null || phone == null || phone > pendingVersion) return clearPendingWatch()
        if (phone < pendingVersion || job?.isActive == true) return
        hidden = false
        job = scope.launch(Dispatchers.IO) {
            val watch = watchVersion()
            if (watch == null || !isOlder(watch, pendingVersion)) return@launch clearPendingWatch()
            if (reachableWatch() == null) return@launch
            clearPendingWatch()
            val failure = try {
                val apk = File(dir, watchApkName(pending)).takeIf { it.isFile }
                    ?: latestRelease()?.takeIf { it.version == pendingVersion }?.watchApk
                        ?.let { download(it, watchApkName(pending), UpdateState.Step.DOWNLOADING_WATCH) }
                    ?: return@launch
                sendToWatch(apk, pending) ?: run {
                    publish(UpdateState.Done(watchSent = true, phoneInstalling = false))
                    null
                }
            } catch (e: IOException) {
                Log.w(TAG, "Watch update failed", e)
                UpdateState.Reason.NETWORK
            } catch (e: Exception) {
                Log.w(TAG, "Watch update failed", e)
                UpdateState.Reason.WATCH_FAILED
            }
            failure?.let { publish(UpdateState.Failed(it)) }
        }
    }

    private fun clearPendingWatch() = prefs.edit().remove(KEY_PENDING_WATCH).apply()

    private fun watchApkName(version: String) = "BookSound-Watch-$version.apk"

    /** Hides the dialog; a download or a sending to the watch goes on behind it. */
    fun dismiss() {
        hidden = job?.isActive == true
        _state.value = UpdateState.Idle
    }

    private fun isOlder(installed: String, latest: AppVersion): Boolean = AppVersion.parse(installed)?.let { it < latest } ?: true

    // ---------------------------------------------------------------- GitHub

    private fun latestRelease(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        api.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("GitHub: ${response.code}")
            val json = JSONObject(response.body.string())
            val tag = json.getString("tag_name")
            val version = AppVersion.parse(tag) ?: return null
            val assets = json.optJSONArray("assets")
            fun asset(name: String): Release.Asset? {
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.getString("name") == name) return Release.Asset(a.getString("browser_download_url"), a.getLong("size"))
                }
                return null
            }
            return Release(version, asset("BookSound-$tag.apk"), asset("BookSound-Watch-$tag.apk"))
        }
    }

    private fun download(asset: Release.Asset, name: String, step: UpdateState.Step): File {
        dir.mkdirs()
        // Earlier releases' APKs; both of this one stay (see the class comment).
        val release = name.substringAfterLast('-')
        dir.listFiles()?.filter { !it.name.endsWith("-$release") && !it.name.endsWith("-$release.part") }?.forEach { it.delete() }
        val target = File(dir, name)
        if (target.length() == asset.size) return target
        publish(UpdateState.Working(step, 0f))
        downloads.newCall(Request.Builder().url(asset.url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download: ${response.code}")
            val tmp = File(dir, "$name.part")
            response.body.byteStream().use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        publish(UpdateState.Working(step, if (asset.size > 0) done.toFloat() / asset.size else null))
                    }
                }
            }
            if (tmp.length() != asset.size || !tmp.renameTo(target)) throw IOException("Download incomplete")
        }
        return target
    }

    // ---------------------------------------------------------------- watch

    /** The version of BookSound on the paired watch, as it last published it. */
    private suspend fun watchVersion(): String? = try {
        val local = Wearable.getNodeClient(context).localNode.await().id
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchPaths.WATCH_INFO_PATH).build()
        val items = data.getDataItems(uri).await()
        try {
            items.filter { it.uri.host != local }.mapNotNull { it.data?.toString(Charsets.UTF_8) }.maxByOrNull { AppVersion.parse(it) ?: AppVersion.parse("0")!! }
        } finally {
            items.release()
        }
    } catch (e: Exception) {
        // No Wear OS on this phone.
        null
    }

    /** A connected watch with BookSound; null when there is none (or no Wear OS on this phone). */
    private suspend fun reachableWatch(): Node? = try {
        capabilities.getCapability(WatchPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await().nodes.firstOrNull()
    } catch (e: Exception) {
        null
    }

    /** Streams the watch APK to the watch; returns null once the watch has it, else why not. */
    private suspend fun sendToWatch(apk: File, version: String): UpdateState.Reason? {
        val node = reachableWatch() ?: return UpdateState.Reason.WATCH_UNREACHABLE
        val result = CompletableDeferred<WatchResult>()
        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path == WatchPaths.APK_RESULT_PREFIX + version) result.complete(WatchResult.decode(event.data))
        }
        messages.addListener(listener, Uri.parse("wear://*" + WatchPaths.APK_RESULT_PREFIX), MessageClient.FILTER_PREFIX).await()
        try {
            publish(UpdateState.Working(UpdateState.Step.SENDING_WATCH, 0f))
            val channel = channels.openChannel(node.id, WatchPaths.APK_PREFIX + version).await()
            try {
                channels.getOutputStream(channel).await().use { output ->
                    apk.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            sent += n
                            publish(UpdateState.Working(UpdateState.Step.SENDING_WATCH, sent.toFloat() / apk.length()))
                        }
                    }
                }
                return when (withTimeoutOrNull(RESULT_TIMEOUT_MS) { result.await() }) {
                    WatchResult.Saved -> null
                    WatchResult.NoSpace -> UpdateState.Reason.WATCH_NO_SPACE
                    else -> UpdateState.Reason.WATCH_FAILED
                }
            } finally {
                runCatching { channels.close(channel).await() }
            }
        } finally {
            messages.removeListener(listener)
        }
    }

    companion object {
        private const val TAG = "UpdateManager"
        const val REPOSITORY = "HuTao1Love/BookSound"
        private const val RESULT_TIMEOUT_MS = 60_000L
        private const val KEY_PENDING_WATCH = "pending_watch"
    }
}
