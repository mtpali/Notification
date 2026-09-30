package com.mtpali.notification

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import java.util.concurrent.atomic.AtomicBoolean

class RelayJobService : JobService() {
    private var active: AtomicBoolean? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val running = AtomicBoolean(true)
        active = running
        RelayClient.flush(applicationContext, { running.get() }) {
            if (running.compareAndSet(true, false)) {
                jobFinished(params, OutboxStore.count(applicationContext) > 0)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active?.set(false)
        return OutboxStore.count(this) > 0
    }

    companion object {
        private const val JOB_ID = 2101

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val first = OutboxStore.first(context)
            if (first == null) {
                scheduler.cancel(JOB_ID)
                return
            }
            val due = maxOf(System.currentTimeMillis() + 1_000, first.attemptAfter)
            val existing = scheduler.getPendingJob(JOB_ID)
            if (existing != null && existing.extras.getLong("due") <= due) return
            try {
                val job = JobInfo.Builder(JOB_ID, ComponentName(context, RelayJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setMinimumLatency((due - System.currentTimeMillis()).coerceAtLeast(1_000))
                    .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setExtras(PersistableBundle().apply { putLong("due", due) })
                    .build()
                if (scheduler.schedule(job) == JobScheduler.RESULT_FAILURE) {
                    Diagnostics.error(context, "Could not schedule retry")
                }
            } catch (_: RuntimeException) {
                Diagnostics.error(context, "Could not schedule retry")
            }
        }
    }
}
