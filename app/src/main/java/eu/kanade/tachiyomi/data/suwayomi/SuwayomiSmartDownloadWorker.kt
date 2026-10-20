// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

/** Account-scoped upkeep runs independently of update notifications and survives process death. */
class SuwayomiSmartDownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("session") ?: return Result.success()
        val backend = Injekt.get<SuwayomiBackend>()
        val session = runCatching { backend.requireSession(id) }.getOrNull() ?: return Result.success()
        val snapshot = session.offline.state.value
        if (snapshot.smartTitles.isEmpty() || snapshot.paused) return Result.success()
        if (snapshot.wifiOnly && !SuwayomiOfflineWorker.wifi(applicationContext)) return Result.retry()
        var retry = false
        for (mangaId in snapshot.smartTitles) {
            try {
                session.flush()
                backend.offlineDownloadMutex.withLock {
                    if (backend.requireSession(id) !== session) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                    if (session.offline.state.value.wifiOnly && !SuwayomiOfflineWorker.wifi(applicationContext)) throw OfflineWifiRequired()
                    session.reconcileSmartDownloads(mangaId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (backend.session.value !== session) return Result.success()
                retry = true
            }
        }
        if (session.offline.next() != null) SuwayomiOfflineWorker.schedule(applicationContext, session)
        return if (retry) Result.retry() else Result.success()
    }

    companion object {
        private fun name(id: String) = "SuwayomiSmartDownloads-$id"
        internal fun schedule(context: Context, session: SuwayomiSession, immediate: Boolean = false) {
            if (!WorkManager.isInitialized()) return
            val manager = WorkManager.getInstance(context)
            if (session.offline.state.value.smartTitles.isEmpty()) {
                cancel(context, session.id)
                return
            }
            val data = workDataOf("session" to session.id)
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresStorageNotLow(true).build()
            val periodic = PeriodicWorkRequestBuilder<SuwayomiSmartDownloadWorker>(1, TimeUnit.HOURS)
                .setInputData(data).setConstraints(constraints).build()
            manager.enqueueUniquePeriodicWork(name(session.id), ExistingPeriodicWorkPolicy.KEEP, periodic)
            if (immediate) {
                val request = OneTimeWorkRequestBuilder<SuwayomiSmartDownloadWorker>().setInputData(data).setConstraints(constraints).build()
                manager.enqueueUniqueWork("${name(session.id)}-now", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }
        internal fun cancel(context: Context, id: String) {
            if (!WorkManager.isInitialized()) return
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(name(id))
                cancelUniqueWork("${name(id)}-now")
            }
        }
    }
}
// KMK <--
