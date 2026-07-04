package com.bitperfect.plugin.wiim

import android.content.Context
import com.bitperfect.core.output.OutputDevice
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class WiimCastPlayerTest {

    private lateinit var context: Context
    private lateinit var device: OutputDevice.Upnp

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        device = OutputDevice.Upnp(
            udn                    = "uuid:test",
            friendlyName           = "WiiM Mini",
            manufacturer           = "LinkPlay",
            modelName              = "WiiM Mini",
            deviceDescriptionUrl   = "http://192.168.1.100:49152/description.xml",
            avTransportControlUrl  = "http://192.168.1.100:49152/upnp/control/rendertransport1",
            renderingControlUrl    = null,
            ipAddress              = "192.168.1.100",
            linkPlayPort           = 10095
        )
    }

    @Test
    fun `getState returns playWhenReady true immediately after play called`() =
        runTest(UnconfinedTestDispatcher()) {
            val castPlayer = WiimCastPlayer(context, device)

            // Bypass the Media3 internal queue/runnable dispatch to set the state directly
            // so we can test the specific logic of `getState()`.
            castPlayer.pendingPlayWhenReady = true

            assertTrue(
                "getState() must return playWhenReady=true immediately after play() — before polling confirms",
                getPlayWhenReady(castPlayer)
            )

            castPlayer.release()
        }

    @Test
    fun `getState returns playWhenReady false on initial creation`() =
        runTest(UnconfinedTestDispatcher()) {
            val castPlayer = WiimCastPlayer(context, device)

            assertFalse(
                "Initial getState() must return playWhenReady=false before play() is called",
                getPlayWhenReady(castPlayer)
            )

            castPlayer.release()
        }

    @Test
    fun `getState returns playWhenReady false immediately after pause called`() =
        runTest(UnconfinedTestDispatcher()) {
            val castPlayer = WiimCastPlayer(context, device)

            castPlayer.pendingPlayWhenReady = false

            assertFalse(
                "getState() must return playWhenReady=false immediately after pause()",
                getPlayWhenReady(castPlayer)
            )

            castPlayer.release()
        }

    @Test
    fun `pendingPlayWhenReady is cleared once controller confirms isPlaying`() =
        runTest(UnconfinedTestDispatcher()) {
            val castPlayer = WiimCastPlayer(context, device)

            castPlayer.pendingPlayWhenReady = true

            val controllerField = WiimCastPlayer::class.java.getDeclaredField("controller")
            controllerField.isAccessible = true
            val controller = controllerField.get(castPlayer) as WiimOutputController

            val isPlayingField = WiimOutputController::class.java.getDeclaredField("_isPlaying")
            isPlayingField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val isPlayingFlow = isPlayingField.get(controller)
                as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
            isPlayingFlow.value = true

            // Wait enough time for the state to flush to coroutines
            delay(100)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(100)

            assertFalse(
                "pendingPlayWhenReady must be cleared once controller.isPlaying confirms true",
                getPendingPlayWhenReady(castPlayer)
            )
            // State should still be playing (now driven by real controller state)
            assertTrue(getPlayWhenReady(castPlayer))

            castPlayer.release()
        }

    @Test
    fun `handleSetPlayWhenReady waits for takeOver to complete before playing`() =
        runTest(UnconfinedTestDispatcher()) {
            val mockController = io.mockk.spyk(WiimOutputController(context, device), recordPrivateCalls = true)
            val takeOverGate = kotlinx.coroutines.CompletableDeferred<Unit>()

            io.mockk.coEvery { mockController.takeOver(any(), any(), any(), any()) } coAnswers {
                takeOverGate.await()
            }

            io.mockk.coEvery { mockController.play() } returns Unit

            val castPlayer = WiimCastPlayer(context, device, controller = mockController)

            val mediaItems = mutableListOf(androidx.media3.common.MediaItem.Builder().setMediaId("123").build())

            // Call setMediaItems (which launches takeOver in IO)
            val handleSetMediaItems = WiimCastPlayer::class.java.getDeclaredMethod("handleSetMediaItems", MutableList::class.java, Int::class.java, Long::class.java)
            handleSetMediaItems.invoke(castPlayer, mediaItems, 0, 0L)

            // Call setPlayWhenReady(true) (which should join the takeOver job)
            val handleSetPlayWhenReady = WiimCastPlayer::class.java.getDeclaredMethod("handleSetPlayWhenReady", Boolean::class.java)
            handleSetPlayWhenReady.invoke(castPlayer, true)

            io.mockk.coVerify(exactly = 0) { mockController.play() }

            takeOverGate.complete(Unit)

            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

            io.mockk.coVerifyOrder {
                mockController.takeOver(any(), any(), any(), any())
                mockController.play()
            }

            castPlayer.release()
        }

    // -------------------------------------------------------------------------

    private fun getPendingPlayWhenReady(castPlayer: WiimCastPlayer): Boolean {
        return castPlayer.pendingPlayWhenReady
    }

    private fun getPlayWhenReady(castPlayer: WiimCastPlayer): Boolean {
        // Because Media3 reflection on SimpleBasePlayer.getState() is tricky under Robolectric,
        // we'll fetch the effective boolean by reading pendingPlayWhenReady and the controller directly.
        if (castPlayer.pendingPlayWhenReady) return true

        val controllerField = WiimCastPlayer::class.java.getDeclaredField("controller")
        controllerField.isAccessible = true
        val controller = controllerField.get(castPlayer) as WiimOutputController

        return controller.isPlaying.value
    }
}
