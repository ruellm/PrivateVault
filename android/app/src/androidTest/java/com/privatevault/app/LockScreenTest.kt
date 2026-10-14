package com.privatevault.app

import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

// T2: the lock screen window carries FLAG_SECURE, and the vault screen
// routes to the lock screen when the process is not unlocked.
@RunWith(AndroidJUnit4::class)
class LockScreenTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val lock = PvApp.of(context).lock

    @Test
    fun lockScreenWindowIsSecure() {
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        ActivityScenario.launch(LockActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val flags = a.window.attributes.flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Test
    fun vaultScreenRoutesToTheLockScreenWhenLocked() {
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The activity finishes itself in onCreate and starts the lock screen.
            Thread.sleep(500)
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
