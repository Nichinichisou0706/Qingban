package io.github.ming.qingban

import android.app.AlarmManager
import android.app.NotificationManager
import io.github.ming.qingban.domain.*
import io.github.ming.qingban.platform.AndroidFocusScheduler
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=android.app.Application::class,manifest=Config.NONE)
class FocusNotificationsTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val alarms get()=shadowOf(context.getSystemService(AlarmManager::class.java))
    @Test fun countdownUsesStoredDeadline()=runBlocking {
        val run=FocusRun("timer",startedAt=1_000,targetMillis=60_000)
        AndroidFocusScheduler(context).reconcile(run,true)
        assertEquals(61_000L,alarms.nextScheduledAlarm!!.triggerAtTime)
    }
    @Test fun pausedTimerCancelsAlarm()=runBlocking {
        val scheduler=AndroidFocusScheduler(context); val run=FocusRun("timer",startedAt=1_000)
        scheduler.reconcile(run,true); scheduler.reconcile(run.copy(status="paused",startedAt=null),true)
        assertNull(alarms.nextScheduledAlarm)
    }
    @Test fun disabledNotificationsCancelPendingAlarm()=runBlocking {
        val scheduler=AndroidFocusScheduler(context); val run=FocusRun("timer",startedAt=1_000)
        scheduler.reconcile(run,true); scheduler.reconcile(run,false); assertNull(alarms.nextScheduledAlarm)
    }
    @Test fun completionPostsFocusChannelNotification()=runBlocking {
        AndroidFocusScheduler(context).completed(FocusRun("timer",title="写方案"))
        val notification=shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("timer".hashCode())
        assertEquals("qingban_focus",notification.channelId); assertEquals("写方案",notification.extras.getString("android.text"))
    }
}
