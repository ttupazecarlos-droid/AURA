package com.example

/**
 * ARCHIVO: MainActivityLockTest.kt
 * FUNCIÓN: Pruebas Robolectric del bloqueo en MainActivity. Comprueban que un teléfono sin método
 *          de seguridad permanece bloqueado, que las credenciales nativas solo desbloquean con
 *          RESULT_OK, que los intents de audio esperan al desbloqueo y que FLAG_SECURE protege
 *          la vista de recientes.
 * NAVEGACIÓN: MainActivity -> AppLock; usa Robolectric para simular ciclo de vida e intents.
 */

import android.content.Intent
import android.app.Activity
import android.app.KeyguardManager
import android.net.Uri
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.activity.result.contract.ActivityResultContracts
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
    @Config(sdk = [28, 34])
    fun `shared audio uses EXTRA_STREAM and rejects the wrong parcelable type`() {
        val uri = Uri.parse("content://test/music.flac")
        assertEquals(uri, incomingAudioUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)))
        assertNull(incomingAudioUri(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, Intent())))
        assertNull(incomingAudioUri(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/music.flac"))))
    }

    @Test
    fun `picker results reach the registry only after successful authentication`() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            val lock = ViewModelProvider(activity)[AppLock::class.java]
            var received: Uri? = null
            val launcher = activity.activityResultRegistry.register(
                "protected_folder", ActivityResultContracts.OpenDocumentTree()
            ) { received = it }
            launcher.launch(null)
            val request = shadowOf(activity).nextStartedActivityForResult
            val uri = Uri.parse("content://test/tree/music")
            shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            assertNull(received)
            lock.beginAuthentication()
            lock.beginDeviceCredential()
            shadowOf(activity).callOnActivityResult(1001, Activity.RESULT_OK, null)
            assertEquals(uri, received)
            assertTrue(lock.takeActivityResults().isEmpty())
            launcher.unregister()
        }
    }

    // Sin PIN/patrón/contraseña configurados no se puede autenticar ni abrir la app.
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

    // En Android 7-10 sin huellas se abre la confirmación nativa y cancelar no desbloquea.
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

    // Solo el RESULT_OK del flujo de credenciales concede acceso al volver a la actividad.
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

    // Un intent ACTION_VIEW no importa audio hasta desbloquear y FLAG_SECURE oculta la vista previa.
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
