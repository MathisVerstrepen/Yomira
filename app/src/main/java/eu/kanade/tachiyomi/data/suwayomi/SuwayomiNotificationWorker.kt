// KMK -->
package eu.kanade.tachiyomi.data.suwayomi

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.updater.AppUpdateJob
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tachiyomi.core.common.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.TimeUnit

class SuwayomiNotificationWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val expected = inputData.getString("session") ?: return Result.success()
        if (!enabled(applicationContext)) return Result.success()
        val backend = Injekt.get<SuwayomiBackend>()
        val session = runCatching { backend.requireSession(expected) }.getOrNull() ?: return Result.success()
        try {
            val saved = session.notificationCheckpoint()
            val checkpoint = saved?.let { Json.decodeFromString<SuwayomiUpdateCheckpoint>(it) } ?: SuwayomiUpdateCheckpoint()
            val chapters = mutableListOf<ServerActivityChapter>()
            var cursor: String? = null
            // Read all timestamp ties; a fixed first-page slice cannot safely deduplicate a large update.
            do {
                val page = session.activityPage(false, cursor)
                chapters.addAll(page.nodes.takeWhile { it.fetchedAt >= checkpoint.fetchedAt })
                val newest = chapters.maxOfOrNull { it.fetchedAt } ?: 0
                val floor = if (saved == null) newest else checkpoint.fetchedAt
                if (!page.pageInfo.hasNextPage || page.nodes.any { it.fetchedAt < floor }) break
                val next = page.pageInfo.endCursor
                if (next == null || next == cursor || page.nodes.isEmpty()) throw SuwayomiException(SuwayomiFailure.CONTRACT)
                cursor = next
            } while (true)
            val changed = chapters.distinctBy { it.id }.filterNot(checkpoint::contains)
            val next = checkpoint.advance(chapters)
            backend.inSession(expected) { active ->
                if (active !== session) throw SuwayomiException(SuwayomiFailure.SESSION_CHANGED)
                if (saved != null && changed.isNotEmpty() && canNotify(applicationContext)) {
                    val manager = NotificationManagerCompat.from(applicationContext)
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.stringResource(tachiyomi.i18n.kmk.KMR.strings.suwayomi_notifications), NotificationManager.IMPORTANCE_DEFAULT))
                    val intent = Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    val pending = PendingIntent.getActivity(applicationContext, ID, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val title = applicationContext.stringResource(tachiyomi.i18n.kmk.KMR.strings.suwayomi_new_server_chapters, changed.size)
                    val notification = NotificationCompat.Builder(applicationContext, CHANNEL).setSmallIcon(R.drawable.ic_komikku).setContentTitle(title)
                        .setContentText(changed.map { it.manga.title }.distinct().take(3).joinToString(" · ")).setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).build()
                    // Persist before posting: a crash cannot repeatedly alert the same update. A crash between
                    // this write and notify may miss one alert; Android delivery is deliberately best effort.
                    session.saveNotificationCheckpoint(Json.encodeToString(SuwayomiUpdateCheckpoint.serializer(), next))
                    manager.notify(ID, notification)
                } else {
                    session.saveNotificationCheckpoint(Json.encodeToString(SuwayomiUpdateCheckpoint.serializer(), next))
                }
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if ((e as? SuwayomiException)?.failure in listOf(SuwayomiFailure.AUTHENTICATION_REQUIRED, SuwayomiFailure.PERMISSION_DENIED, SuwayomiFailure.SESSION_CHANGED, SuwayomiFailure.CONTRACT)) Result.failure() else Result.retry()
        }
    }

    companion object {
        private const val WORK = "SuwayomiChapterChecks"
        private const val CHANNEL = "suwayomi_server_chapters"
        private const val ID = 70140
        private fun preferences(context: Context) = context.getSharedPreferences("suwayomi-background", Context.MODE_PRIVATE)
        fun enabled(context: Context): Boolean = preferences(context).getBoolean("enabled", false)
        fun canNotify(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
        fun setEnabled(context: Context, enabled: Boolean, sessionId: String) {
            preferences(context).edit().putBoolean("enabled", enabled).apply()
            schedule(context, sessionId)
        }
        fun schedule(context: Context, sessionId: String) {
            if (!WorkManager.isInitialized()) return
            if (!enabled(context)) {
                cancel(context)
                return
            }
            val request = PeriodicWorkRequestBuilder<SuwayomiNotificationWorker>(1, TimeUnit.HOURS)
                .setInputData(workDataOf("session" to sessionId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
        fun cancel(context: Context) {
            if (WorkManager.isInitialized()) WorkManager.getInstance(context).cancelUniqueWork(WORK)
            NotificationManagerCompat.from(context).cancel(ID)
        }
        fun initialize(context: Context, scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
            val prefs = preferences(context)
            if (!prefs.getBoolean("localWorkRetired", false)) {
                WorkManager.getInstance(context).cancelAllWork().result.get()
                prefs.edit().putBoolean("localWorkRetired", true).commit()
            }
            AppUpdateJob.setupTask(context)
            Injekt.get<SuwayomiBackend>().session.value?.let { schedule(context, it.id) }
        }
    }
}
// KMK <--
