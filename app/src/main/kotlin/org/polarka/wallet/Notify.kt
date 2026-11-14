package org.polarka.wallet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import kotlin.concurrent.thread

/**
 * Tells the owner when a block reward or a payment arrives, also while the app is closed.
 *
 * About every fifteen minutes the phone's own job scheduler lets the app ask the node what is new. It needs
 * no secret for that: only the node account and the public key of the wallet, which are stored for this.
 * It is best effort: the phone decides when the job runs, and it needs the connection to the node (Tailscale).
 */
object Notifier {
    private const val PREFS = "north.notify"
    private const val ENABLED = "enabled"
    private const val LAST_HEIGHT = "lastHeight"
    private const val CHANNEL = "incoming"
    private const val JOB = 1867

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun enable(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, on).remove(LAST_HEIGHT).apply()
        runCatching { schedule(context) }
    }

    /** Sets the periodic job up when notifications are on, and removes it when they are off. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(context)) {
            scheduler.cancel(JOB)
            return
        }
        if (scheduler.getPendingJob(JOB) != null) return
        val job = JobInfo.Builder(JOB, ComponentName(context, WatchJob::class.java))
            .setPeriodic(15 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    /** One look at the node. The first look only remembers where the chain stands; later ones report what is new. */
    fun check(context: Context) {
        if (!enabled(context)) return
        val setup = Stores.open(context).load() ?: return
        val wallet = Stores.wallet(context).load()
        val last = prefs(context).getInt(LAST_HEIGHT, -1)
        val (tip, incoming) = Engine(setup).incomingSince(wallet, last)
        if (last >= 0) incoming.forEachIndexed { n, item -> show(context, item, n) }
        prefs(context).edit().putInt(LAST_HEIGHT, tip).apply()
    }

    private fun show(context: Context, item: Incoming, n: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notify_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = context.getString(if (item.move.coinbase) R.string.notify_reward else R.string.notify_payment)
        val where = context.getString(if (item.own) R.string.history_wallet else R.string.history_watched)
        val text = "+" + Format.coins(item.move.receivedSat) + " NORTH, " + where + ", " + context.getString(R.string.notify_block, item.move.height)
        // on a locked screen only the fact is shown, not the amount
        val hidden = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground).setContentTitle(title).build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(hidden)
            .build()
        manager.notify(item.move.txid.hashCode() + n, notification)
    }
}

/** The job the phone runs for [Notifier]. It does its work on its own thread and always reports that it is done. */
class WatchJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread(name = "polarka-watch-job") {
            try {
                Notifier.check(applicationContext)
            } catch (e: Exception) {
                // the node could not be reached or answered with an error: the next run tries again
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}
