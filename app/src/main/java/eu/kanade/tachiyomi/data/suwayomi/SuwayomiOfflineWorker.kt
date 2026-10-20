// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.kmk.KMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class SuwayomiOfflineWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session") ?: return Result.success()
        val backend = Injekt.get<SuwayomiBackend>()
        val session = runCatching { backend.requireSession(id) }.getOrNull() ?: return Result.success()
        return backend.offlineDownloadMutex.withLock { if (backend.session.value !== session) Result.success() else download(session, backend, id) }
    }

    private suspend fun download(session: SuwayomiSession, backend: SuwayomiBackend, id: String): Result {
        val store = session.offline
        while (true) {
            val item = store.next() ?: return Result.success()
            try {
                setForeground(notification(session, item))
                SuwayomiOfflineDownloader(store, session::chapter, session::pages, session::offlineImage, {
                    backend.requireSession(id)
                    if (store.state.value.wifiOnly && !wifi(applicationContext)) throw OfflineWifiRequired()
                }, { progress -> setForeground(notification(session, progress)) }).download(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (backend.session.value !== session) return Result.success()
                val state = when {
                    e is OfflineWifiRequired -> OfflineState.WIFI
                    e is OfflineStorageFull -> OfflineState.STORAGE
                    e is SuwayomiException && e.failure in listOf(SuwayomiFailure.NETWORK, SuwayomiFailure.SERVER) -> OfflineState.NETWORK
                    else -> OfflineState.ERROR
                }
                store.update(item.chapterId, state)
                if (state != OfflineState.ERROR) return Result.retry()
            }
        }
    }

    private fun notification(session: SuwayomiSession, item: OfflineDownload): ForegroundInfo {
        val context = applicationContext
        val channel = "suwayomi_phone_downloads"
        val title = session.offline.state.value.titles[item.mangaId]?.manga?.title.orEmpty()
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel, context.stringResource(KMR.strings.suwayomi_phone_downloads), NotificationManager.IMPORTANCE_LOW))
        // KMK -->
        val notification = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_yomira_mark)
            // KMK <--
            .setContentTitle(title).setContentText(context.stringResource(KMR.strings.suwayomi_offline_progress, item.pages, item.total))
            .setProgress(item.total, item.pages, item.total == 0).setOngoing(true).setOnlyAlertOnce(true).build()
        return ForegroundInfo(70141, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private fun name(id: String) = "SuwayomiPhoneDownloads-$id"
        internal fun wifi(context: Context): Boolean {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        internal fun schedule(context: Context, session: SuwayomiSession, replace: Boolean = false) {
            if (!WorkManager.isInitialized()) return
            val request = OneTimeWorkRequestBuilder<SuwayomiOfflineWorker>()
                .setInputData(workDataOf("session" to session.id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(session.id), if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
        internal fun cancel(context: Context, id: String) {
            if (WorkManager.isInitialized()) WorkManager.getInstance(context).cancelUniqueWork(name(id))
        }
    }
}

/** Reading intents survive process death and are retried even when the reader is no longer open. */
class SuwayomiProgressWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session") ?: return Result.success()
        val session = runCatching { Injekt.get<SuwayomiBackend>().requireSession(id) }.getOrNull() ?: return Result.success()
        try {
            session.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.success()
        }
        return if (session.pending.value.any { it.status == ChapterIntentStatus.PENDING }) Result.retry() else Result.success()
    }
    companion object {
        internal fun schedule(context: Context, id: String) {
            if (!WorkManager.isInitialized()) return
            val request = OneTimeWorkRequestBuilder<SuwayomiProgressWorker>().setInputData(workDataOf("session" to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork("SuwayomiProgress-$id", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
        internal fun cancel(context: Context, id: String) {
            if (WorkManager.isInitialized()) WorkManager.getInstance(context).cancelUniqueWork("SuwayomiProgress-$id")
        }
    }
}
// KMK <--
