package com.journeycontinuity.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.journeycontinuity.app.data.repository.ProbableEndDeadlineIdentity
import com.journeycontinuity.app.data.repository.WorkManagerProbableEndDeadlineScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** Exercises the real unique WorkManager enqueue path in the isolated acceptance UID. */
@RunWith(AndroidJUnit4::class)
class ProbableEndDeadlineWorkManagerTest {
    @Test fun sameEpisodeEnqueuesOneUniqueWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val work = WorkManager.getInstance(context)
        val episodeId = "deadline-scheduler-acceptance"
        val name = "probable-end-deadline-$episodeId"
        val now = System.currentTimeMillis()
        val identity = ProbableEndDeadlineIdentity("test-journey", episodeId,
            "test-candidate", 2, 3, now + 30 * 60_000L)
        try {
            val scheduler = WorkManagerProbableEndDeadlineScheduler(context)
            scheduler.schedule(identity, now)
            scheduler.schedule(identity, now + 60_000)
            val until = System.currentTimeMillis() + 10_000
            var active = 0
            do {
                val infos = work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                active = infos.count { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
                if (active == 0) Thread.sleep(50)
            } while (active == 0 && System.currentTimeMillis() < until)
            assertEquals(1, active)
        } finally {
            work.cancelUniqueWork(name).result.get(10, TimeUnit.SECONDS)
        }
    }
}
