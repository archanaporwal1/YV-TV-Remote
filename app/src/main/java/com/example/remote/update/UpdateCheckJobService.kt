package com.example.remote.update

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.example.remote.data.RemotePreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@SuppressLint("SpecifyJobSchedulerIdRange")
class UpdateCheckJobService : JobService() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var activeJob: Job? = null

  override fun onStartJob(params: JobParameters?): Boolean {
    val prefs = RemotePreferencesStore(applicationContext)
    val settings = prefs.loadSettings()
    if (!settings.autoCheckUpdates) {
      jobFinished(params, false)
      return false
    }

    activeJob =
      serviceScope.launch {
        try {
          val result = AppUpdateManager.checkForUpdate(settings.releaseRepoOverride)
          if (result is UpdateCheckResult.UpdateAvailable) {
            val tag = result.release.tag
            val skipped = prefs.getSkippedUpdateTag()
            val lastNotified = prefs.getLastNotifiedUpdateTag()
            if (tag != skipped && tag != lastNotified) {
              AppUpdateManager.showUpdateNotification(applicationContext, result.release)
              prefs.setLastNotifiedUpdateTag(tag)
            }
          }
        } catch (_: Throwable) {
          // Never crash background update polling
        } finally {
          runCatching { jobFinished(params, false) }
        }
      }
    return true
  }

  override fun onStopJob(params: JobParameters?): Boolean {
    activeJob?.cancel()
    return true
  }

  override fun onDestroy() {
    super.onDestroy()
    serviceScope.cancel()
  }

  companion object {
    private const val UPDATE_JOB_ID = 64680
    private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L

    fun syncSchedule(context: Context, enabled: Boolean) {
      runCatching {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        if (!enabled) {
          scheduler.cancel(UPDATE_JOB_ID)
          return
        }
        val existing = scheduler.allPendingJobs.any { it.id == UPDATE_JOB_ID }
        if (existing) return

        val component = ComponentName(context, UpdateCheckJobService::class.java)
        val info =
          JobInfo.Builder(UPDATE_JOB_ID, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(CHECK_INTERVAL_MS)
            .setPersisted(false)
            .build()
        scheduler.schedule(info)
      }
    }
  }
}
