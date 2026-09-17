package com.botlisa.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the on-device "что ещё" model (~2.7GB) in the background,
 * resumable via HTTP Range requests -- if [OnDeviceLlmConfig.modelFilePath]
 * already has partial bytes on disk (e.g. the app was killed mid-download),
 * this resumes from there instead of restarting from zero. See
 * ON_DEVICE_LLM_PLAN.md Phase 6.
 *
 * Enqueued as unique work keyed by [UNIQUE_WORK_NAME] rather than tracked by
 * a WorkRequest id this app would have to persist itself -- WorkManager
 * already durably persists unique-work state (including progress) across
 * process death, so Settings can always find the current/last attempt by
 * name alone.
 */
// CoroutineWorker is WorkManager's async unit of deferrable background work:
// the system schedules doWork() to run (even across app restarts/reboots,
// per the enqueueUniqueWork call below), retries it on failure per policy,
// and lets it suspend (via `suspend fun`) instead of blocking a thread while
// waiting on I/O like the network download below.
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // setForeground promotes this background work to a foreground
        // service with a visible notification -- required for a
        // multi-minute download so Android doesn't kill the process for
        // running too long in the background.
        setForeground(createForegroundInfo(0))

        val destFile = File(OnDeviceLlmConfig.modelFilePath(applicationContext))
        val freeBytes = StatFs(destFile.parentFile!!.path).availableBytes
        if (freeBytes < REQUIRED_FREE_BYTES) {
            OnDeviceLlmConfig.setModelState(applicationContext, OnDeviceLlmConfig.ModelState.FAILED)
            return Result.failure(workDataOf(KEY_ERROR to "Not enough free space"))
        }

        OnDeviceLlmConfig.setModelState(applicationContext, OnDeviceLlmConfig.ModelState.DOWNLOADING)
        return try {
            download(destFile)
            OnDeviceLlmConfig.setModelState(applicationContext, OnDeviceLlmConfig.ModelState.READY)
            Result.success()
        } catch (e: IOException) {
            Log.e(TAG, "Model download failed", e)
            // Deliberately keep the partial file on disk -- a network hiccup
            // shouldn't throw away download progress; the next attempt (a
            // retry or a manually re-tapped download) resumes via Range.
            OnDeviceLlmConfig.setModelState(applicationContext, OnDeviceLlmConfig.ModelState.FAILED)
            Result.retry()
        }
    }

    private suspend fun download(destFile: File) {
        val existingBytes = if (destFile.exists()) destFile.length() else 0L
        val request = Request.Builder()
            .url(MODEL_URL)
            .apply { if (existingBytes > 0) addHeader("Range", "bytes=$existingBytes-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")

            // A server that ignores our Range header and re-sends the whole
            // file (200 instead of 206) would otherwise get appended after
            // our existing partial bytes, corrupting the file -- restart
            // from scratch instead in that case.
            val resuming = existingBytes > 0 && response.code == 206
            if (existingBytes > 0 && !resuming) destFile.delete()

            val startOffset = if (resuming) existingBytes else 0L
            val totalBytes = startOffset + body.contentLength()

            FileOutputStream(destFile, resuming).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var written = startOffset
                    var lastReportedProgress = -1
                    while (true) {
                        if (isStopped) throw IOException("Download cancelled")
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        written += read
                        val progress = if (totalBytes > 0) ((written * 100) / totalBytes).toInt() else 0
                        if (progress != lastReportedProgress) {
                            lastReportedProgress = progress
                            setProgress(workDataOf(KEY_PROGRESS to progress))
                            setForeground(createForegroundInfo(progress))
                        }
                    }
                }
            }

            // Basic corruption check -- at minimum, catch a truncated
            // transfer before marking the model READY and letting
            // OnDeviceLlm try (and fail confusingly) to load a partial file.
            if (totalBytes > 0 && destFile.length() != totalBytes) {
                destFile.delete()
                throw IOException("Downloaded size ${destFile.length()} != expected $totalBytes")
            }
        }
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Model download", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading on-device AI model")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ModelDownloadWorker"
        const val UNIQUE_WORK_NAME = "model_download"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 4201
        private const val BUFFER_SIZE = 64 * 1024

        // ~2.7GB model; require real headroom beyond its own size so the
        // download can't leave the device critically low on space.
        private const val REQUIRED_FREE_BYTES = 3_500_000_000L

        // Same source used in llm_lab/README.md "Setup" to hand-verify this
        // model's output quality before this download path existed.
        private const val MODEL_URL =
            "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf"

        // OkHttp's readTimeout bounds the gap between socket reads, not the
        // whole call -- a multi-minute download over a steady connection
        // won't trip it; a connection that actually stalls will, handing
        // control back to WorkManager's retry policy.
        private val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        // True if the active network is WiFi (unmetered) right now -- used
        // to decide whether enqueue() can auto-start silently or needs to
        // ask first before spending cellular data on a ~2.7GB download.
        // Transport-based, not NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
        // to match exactly what setRequiredNetworkType(UNMETERED) below will
        // itself wait for.
        fun isOnWifi(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        // Context is Android's handle to system services and app resources
        // (here, WorkManager's instance and the app's own files dir) --
        // most Android APIs need one to know which app/process they're
        // acting on behalf of.
        /**
         * Enqueues the download if not already running/queued. Wifi-only by
         * default, matching [OnDeviceTranslator]'s precedent -- if there's no
         * WiFi right now, this just queues silently and waits, potentially
         * indefinitely, for WiFi to show up, so callers that can't guarantee
         * that (see MainActivity's isOnWifi() checks) should ask the
         * caregiver first rather than enqueueing blind.
         *
         * @param allowCellular Pass true only after the caregiver has
         * explicitly agreed to spend cellular data -- switches the
         * constraint to any connectivity and REPLACEs (not KEEPs) an
         * existing queued/WiFi-waiting attempt, since KEEP would otherwise
         * leave that attempt's stricter WiFi-only constraint in place.
         */
        fun enqueue(context: Context, allowCellular: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (allowCellular) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .build(),
                )
                .build()
            val policy = if (allowCellular) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        }
    }
}
