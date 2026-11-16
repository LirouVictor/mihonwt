package eu.kanade.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.delay
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import kotlin.time.Duration.Companion.seconds

/**
 * Shows the translation queue's progress for as long as it runs, as a foreground service so Android doesn't stop the
 * app mid-chapter once it leaves the screen. The queue itself runs in the [ChapterTranslator].
 */
class TranslationWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    @Inject private lateinit var translator: ChapterTranslator

    @Inject private lateinit var notifier: TranslationNotifier

    init {
        context.metroGraph<AppGraph>().inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return ForegroundInfo(
            Notifications.ID_TRANSLATION_PROGRESS,
            notifier.progressNotification(translator.queueState.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
    }

    override suspend fun doWork(): Result {
        if (!translator.isRunning) return Result.success()
        setForegroundSafely()
        try {
            while (translator.isRunning) {
                applicationContext.notify(
                    Notifications.ID_TRANSLATION_PROGRESS,
                    notifier.progressNotification(translator.queueState.value),
                )
                delay(1.seconds)
            }
        } finally {
            // A worker replaced by a newer one leaves the notification to it
            if (!translator.isRunning) applicationContext.cancelNotification(Notifications.ID_TRANSLATION_PROGRESS)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "Translator"

        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<TranslationWorker>()
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
