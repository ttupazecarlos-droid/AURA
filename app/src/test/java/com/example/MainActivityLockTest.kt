package com.example

import android.content.Intent
import android.app.Activity
import android.app.KeyguardManager
import android.net.Uri
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import com.example.aura.auth.AppLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLockTest {
    @Test
    fun `a phone without a screen lock stays blocked and does not launch credentials`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            val lock = ViewModelProvider(activity)[AppLock::class.java]
            assertFalse(lock.isUnlocked)
            assertFalse(lock.isAuthenticating)
            assertTrue(lock.message.contains("Configura un PIN"))
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            shadowOf(activity).callOnActivityResult(1001, Activity.RESULT_OK, null)
            assertFalse(lock.isUnlocked)
        }
    }

    @Test
    @Config(sdk = [28])
    fun `a phone without fingerprints opens native credentials and cancellation cannot unlock`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.create().get()
            shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
            controller.start().resume()
            val lock = ViewModelProvider(activity)[AppLock::class.java]
            val request = shadowOf(activity).nextStartedActivityForResult
            assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", request.intent.action)
            assertTrue(lock.isConfirmingDeviceCredential)
            assertFalse(lock.isUnlocked)
            controller.pause().stop()
            assertTrue(lock.isAuthenticating)
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
            controller.start().resume()
            assertFalse(lock.isUnlocked)
            assertFalse(lock.isAuthenticating)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        }
    }

    @Test
    @Config(sdk = [29])
    fun `only successful native credential confirmation unlocks after returning to the app`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.create().get()
            shadowOf(activity.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
            controller.start().resume()
            val request = shadowOf(activity).nextStartedActivityForResult
            val lock = ViewModelProvider(activity)[AppLock::class.java]
            assertFalse(lock.isUnlocked)
            controller.pause().stop()
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, null)
            controller.start().resume()
            assertTrue(lock.isUnlocked)
            assertFalse(lock.isAuthenticating)
            assertFalse(lock.isConfirmingDeviceCredential)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
        }
    }

    @Test
    fun `external audio intents remain pending while locked and previews are protected`() {
        val uri = Uri.parse("content://test/music.mp3")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        Robolectric.buildActivity(MainActivity::class.java, intent).use { controller ->
            val activity = controller.setup().get()
            assertFalse(ViewModelProvider(activity)[AppLock::class.java].isUnlocked)
            assertEquals(uri, activity.intent.data)
            assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)

            val nextUri = Uri.parse("content://test/next.mp3")
            controller.newIntent(Intent(Intent.ACTION_VIEW, nextUri))
            assertEquals(nextUri, activity.intent.data)
            assertFalse(ViewModelProvider(activity)[AppLock::class.java].isUnlocked)
        }
    }
}
