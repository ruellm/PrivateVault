package com.privatevault.app

import android.content.Intent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

// T2: the gallery and viewer windows carry FLAG_SECURE; thumbnails and the
// viewer bitmap are erased when the activities stop and when the vault
// locks. Runs on the app vault through the lock controller with a throwaway
// PIN; the vault is erased afterwards.
@RunWith(AndroidJUnit4::class)
class ViewerScreenTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val app = PvApp.of(context)
    private val lock = app.lock
    private val pin = "246810".toByteArray(Charsets.US_ASCII)
    private val ids = ArrayList<ByteArray>()

    @Before
    fun setUp() {
        lock.testHooks.kdfOverride = 2L to (64L shl 20)
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
        assertTrue(lock.setup(pin) is LockController.Result.Ok)
        val vault = app.vaultSession.vault!!
        for (name in listOf("orient_1", "orient_6")) {
            val bytes = assets.open("$name.jpg").use { it.readBytes() }
            ids.add(vault.encryptItemArray(bytes, 0, bytes.size, Vault.ItemMeta(NativeBridge.MEDIA_JPEG, 0, 64, 32, 1791849600000L)).id)
        }
    }

    @After
    fun tearDown() {
        if (lock.state == LockState.UNLOCKED) lock.lock("test")
        lock.erase()
        lock.testHooks.kdfOverride = null
    }

    private fun <A : android.app.Activity> waitFor(scenario: ActivityScenario<A>, what: String, check: (A) -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            var ok = false
            scenario.onActivity { a -> ok = check(a) }
            if (ok) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun galleryWindowIsSecureAndThumbnailsAreErasedOnStop() {
        ActivityScenario.launch(GalleryActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            waitFor(scenario, "two thumbnails") { a -> a.cache.size == 2 }
            val erased = PhotoLoader.erased.get()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { a -> assertEquals(0, a.cache.size) }
            assertEquals(erased + 2, PhotoLoader.erased.get())
        }
    }

    @Test
    fun viewerWindowIsSecureAndBitmapIsErasedOnStop() {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, NativeBridge.hex(ids[1]))
        ActivityScenario.launch<ViewerActivity>(intent).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            waitFor(scenario, "the photo") { a -> a.lastResult is PhotoLoader.Loaded.Photo }
            scenario.onActivity { a -> assertTrue(a.hasBitmap) }
            val erased = PhotoLoader.erased.get()
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { a -> assertFalse(a.hasBitmap) }
            assertEquals(erased + 1, PhotoLoader.erased.get())
        }
    }

    @Test
    fun lockFinishesTheViewerAndErasesTheBitmap() {
        val intent = Intent(context, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, NativeBridge.hex(ids[0]))
        val scenario = ActivityScenario.launch<ViewerActivity>(intent)
        waitFor(scenario, "the photo") { a -> a.lastResult is PhotoLoader.Loaded.Photo }
        val erased = PhotoLoader.erased.get()
        lock.lock("test")
        val deadline = System.nanoTime() + 10_000_000_000L
        while (scenario.state != Lifecycle.State.DESTROYED && System.nanoTime() < deadline) Thread.sleep(100)
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        assertTrue(PhotoLoader.erased.get() >= erased + 1)
        scenario.close()
    }
}
