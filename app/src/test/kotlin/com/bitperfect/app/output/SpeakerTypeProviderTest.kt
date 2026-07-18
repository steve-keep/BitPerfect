package com.bitperfect.app.output

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import com.bitperfect.core.output.OutputDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeakerTypeProviderTest {

    private lateinit var provider: SpeakerTypeProvider
    private lateinit var mockOutputRepository: OutputRepository
    private lateinit var testScope: TestScope
    private lateinit var activeDeviceFlow: MutableStateFlow<OutputDevice>
    private lateinit var carConnectionTypeLiveData: MutableLiveData<Int>

    @Before
    fun setUp() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        testScope = TestScope(testDispatcher)

        val context = ApplicationProvider.getApplicationContext<Context>()

        carConnectionTypeLiveData = MutableLiveData(CarConnection.CONNECTION_TYPE_NOT_CONNECTED)
        mockkConstructor(CarConnection::class)
        every { anyConstructed<CarConnection>().type } returns carConnectionTypeLiveData

        activeDeviceFlow = MutableStateFlow(OutputDevice.ThisPhone)
        mockOutputRepository = mockk {
            every { activeDevice } returns activeDeviceFlow
        }

        provider = SpeakerTypeProvider(context, testScope)
        provider.setOutputRepository(mockOutputRepository)
    }

    @After
    fun tearDown() {
        provider.clear()
        unmockkAll()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is OTHER`() = runTest {
        assertEquals(SpeakerType.OTHER, provider.getCurrentType())
        assertEquals(SpeakerType.OTHER, provider.speakerType.value)
    }

    @Test
    fun `when Android Auto is connected, state updates to ANDROID_AUTO`() = runTest {
        carConnectionTypeLiveData.value = CarConnection.CONNECTION_TYPE_PROJECTION
        advanceUntilIdle()

        assertEquals(SpeakerType.ANDROID_AUTO, provider.getCurrentType())
    }

    @Test
    fun `when active device is Bluetooth, state updates to HEADPHONES`() = runTest {
        activeDeviceFlow.value = OutputDevice.Bluetooth("00:11:22:33:44:55", "Test Headphones", null)
        advanceUntilIdle()

        assertEquals(SpeakerType.HEADPHONES, provider.getCurrentType())
    }

    @Test
    fun `when active device is not Bluetooth and Android Auto is disconnected, state is OTHER`() = runTest {
        carConnectionTypeLiveData.value = CarConnection.CONNECTION_TYPE_PROJECTION
        advanceUntilIdle()
        assertEquals(SpeakerType.ANDROID_AUTO, provider.getCurrentType())

        carConnectionTypeLiveData.value = CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        val mockUsbDevice = mockk<android.hardware.usb.UsbDevice>()
        every { mockUsbDevice.deviceId } returns 1
        activeDeviceFlow.value = OutputDevice.UsbDac(mockUsbDevice, mockk(), "Test DAC")
        advanceUntilIdle()

        assertEquals(SpeakerType.OTHER, provider.getCurrentType())
    }

    @Test
    fun `prioritizes Android Auto over Bluetooth when both are connected`() = runTest {
        carConnectionTypeLiveData.value = CarConnection.CONNECTION_TYPE_PROJECTION
        activeDeviceFlow.value = OutputDevice.Bluetooth("00:11:22:33:44:55", "Test Headphones", null)
        advanceUntilIdle()

        assertEquals(SpeakerType.ANDROID_AUTO, provider.getCurrentType())
    }
}
