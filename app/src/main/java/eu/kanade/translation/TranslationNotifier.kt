package eu.kanade.translation

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.notify
import eu.kanade.translation.model.Translation
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.wt.WTMR
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/** Shows the translation queue's progress, and the chapters it translated or failed while those notices are up. */
@Inject
@SingleIn(AppScope::class)
class TranslationNotifier(
    private val context: Context,
    private val securityPreferences: SecurityPreferences,
) {

    private val translated = mutableListOf<Translation>()
    private val failed = mutableListOf<Pair<Translation, String>>()

    private val hideContent: Boolean
        get() = securityPreferences.hideNotificationContent.get()

    fun progressNotification(queue: List<Translation>): Notification {
        val current = queue.firstOrNull { it.status == Translation.State.TRANSLATING }
        val waiting = queue.count { it.status == Translation.State.QUEUE }
        val progress = current?.progress
        val (done, total) = when {
            progress == null -> 0 to 0
            progress.page < progress.pages -> progress.page to progress.pages
            else -> progress.translated to progress.toTranslate
        }
        return context.notificationBuilder(Notifications.CHANNEL_TRANSLATION_PROGRESS) {
            setSmallIcon(MaterialSymbolsR.drawable.rounded_translate)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setContentTitle(
                if (current == null || hideContent) {
                    context.stringResource(WTMR.strings.translation_notifier_title)
                } else {
                    current.label()
                },
            )
            val text = context.translationProgressText(progress)
            setContentText(
                if (waiting > 0) {
                    "$text · " + context.stringResource(WTMR.strings.translation_notifier_queued, waiting)
                } else {
                    text
                },
            )
            setProgress(total, done, total == 0)
            current?.let { setContentIntent(NotificationReceiver.openEntryPendingActivity(context, it.manga.id)) }
        }.build()
    }

    @Synchronized
    fun onTranslated(translation: Translation) {
        if (failed.removeAll { it.first.chapter.id == translation.chapter.id }) showFailed()
        if (!isShowing(Notifications.ID_TRANSLATION_COMPLETE)) translated.clear()
        translated += translation
        val first = translated.first()
        showList(
            id = Notifications.ID_TRANSLATION_COMPLETE,
            channel = Notifications.CHANNEL_TRANSLATION_COMPLETE,
            icon = MaterialSymbolsR.drawable.rounded_translate,
            title = if (translated.size == 1) {
                context.stringResource(WTMR.strings.translation_notifier_done)
            } else {
                context.stringResource(WTMR.strings.translation_notifier_done_many, translated.size)
            },
            lines = translated.map { it.label() },
            // Opens the earliest of them, which is the one to read next
            intent = PendingIntent.getActivity(
                context,
                Notifications.ID_TRANSLATION_COMPLETE,
                ReaderActivity.newIntent(context, first.manga.id, first.chapter.id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    @Synchronized
    fun onFailed(translation: Translation, error: String) {
        if (!isShowing(Notifications.ID_TRANSLATION_ERROR)) failed.clear()
        failed.removeAll { it.first.chapter.id == translation.chapter.id }
        failed += translation to error
        showFailed()
    }

    private fun showFailed() {
        if (failed.isEmpty()) {
            context.cancelNotification(Notifications.ID_TRANSLATION_ERROR)
            return
        }
        showList(
            id = Notifications.ID_TRANSLATION_ERROR,
            channel = Notifications.CHANNEL_TRANSLATION_ERROR,
            icon = MaterialSymbolsR.drawable.rounded_filled_warning,
            title = if (failed.size == 1) {
                context.stringResource(WTMR.strings.translation_notifier_failed)
            } else {
                context.stringResource(WTMR.strings.translation_notifier_failed_many, failed.size)
            },
            lines = failed.map { (translation, error) -> "${translation.label()}: $error" },
            // The entry's chapter menu is where a failed chapter is retried
            intent = NotificationReceiver.openEntryPendingActivity(context, failed.last().first.manga.id),
        )
    }

    private fun showList(id: Int, channel: String, icon: Int, title: String, lines: List<String>, intent: PendingIntent) {
        context.notify(id, channel) {
            setSmallIcon(icon)
            setContentTitle(title)
            if (!hideContent) {
                setContentText(lines.last())
                setStyle(
                    if (lines.size == 1) {
                        NotificationCompat.BigTextStyle().bigText(lines.single())
                    } else {
                        NotificationCompat.InboxStyle().also { style -> lines.takeLast(5).forEach(style::addLine) }
                    },
                )
            }
            setContentIntent(intent)
            setAutoCancel(true)
            setOnlyAlertOnce(true)
        }
    }

    private fun isShowing(id: Int): Boolean = context.notificationManager.activeNotifications.any { it.id == id }

    private fun Translation.label() = "${manga.title} - ${chapter.name}"
}

/** Describes where a queued chapter's translation is, or that it waits in the queue when [progress] is null. */
fun Context.translationProgressText(progress: Translation.Progress?): String = when {
    progress == null -> stringResource(WTMR.strings.translation_queued)
    progress.page < progress.pages -> stringResource(WTMR.strings.translation_progress_ocr, progress.page + 1, progress.pages)
    progress.retries > 0 -> stringResource(WTMR.strings.translation_progress_ai_retry, progress.retries)
    else -> stringResource(WTMR.strings.translation_progress_ai, progress.translated, progress.toTranslate)
}
