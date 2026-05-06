package com.bitperfect.app.usb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitperfect.core.models.DiscMetadata
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.TocEntry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(manifest=Config.NONE)
class RipSessionTest {

    private lateinit var context: Context
    private lateinit var ripSession: RipSession
    private lateinit var dummyToc: DiscToc
    private lateinit var dummyMetadata: DiscMetadata

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // We use a clean instance for each test to isolate state
        ripSession = RipSession(context)

        dummyToc = DiscToc(
            leadOutLba = 5000,
            tracks = listOf(
                TocEntry(trackNumber = 1, lba = 150)
            )
        )
        dummyMetadata = DiscMetadata(
            albumTitle = "Test Album",
            artistName = "Test Artist",
            trackTitles = listOf("Track 1"),
            mbReleaseId = ""
        )
    }

    @Test
    fun startRip_whenAlreadyRipping_isNoOp() {
        // Start first rip
        ripSession.startRip(
            outputFolderUriString = "content://dummy",
            toc = dummyToc,
            metadata = dummyMetadata,
            expectedChecksums = emptyMap(),
            artworkBytes = null
        )

        // The coroutine that runs startRipping finishes quickly in Robolectric because endpoints are missing.
        // It catches the error and finishes, which resets isRipping to false.
        // We need to inject a mock or just test the state synchronously before it finishes.

        val initialStates = ripSession.ripStates.value

        // Since RipManager finishes immediately when transport is null, we can force isRipping
        // via reflection to test the guard condition.
        val isRippingField = RipSession::class.java.getDeclaredField("_isRipping")
        isRippingField.isAccessible = true
        val stateFlow = isRippingField.get(ripSession) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        stateFlow.value = true

        // Try starting a second rip with different data
        val differentToc = dummyToc.copy(tracks = listOf(TocEntry(1, 150), TocEntry(2, 200)))
        ripSession.startRip(
            outputFolderUriString = "content://dummy2",
            toc = differentToc,
            metadata = dummyMetadata,
            expectedChecksums = emptyMap(),
            artworkBytes = null
        )

        // Assert state is unchanged
        assertTrue(ripSession.isRipping.value)
        assertFalse(ripSession.ripStates.value.isEmpty())
        assertEquals(initialStates, ripSession.ripStates.value)
    }

    @Test
    fun clearResults_whileRipping_isNoOp() {
        // Start rip
        ripSession.startRip(
            outputFolderUriString = "content://dummy",
            toc = dummyToc,
            metadata = dummyMetadata,
            expectedChecksums = emptyMap(),
            artworkBytes = null
        )

        // Force isRipping to true
        val isRippingField = RipSession::class.java.getDeclaredField("_isRipping")
        isRippingField.isAccessible = true
        val stateFlow = isRippingField.get(ripSession) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        stateFlow.value = true

        // It might be empty if collect didn't run, but let's assert size 1 due to sync copy
        assertEquals(1, ripSession.ripStates.value.size)

        // Attempt to clear
        ripSession.clearResults()

        // Assert not cleared
        assertEquals(1, ripSession.ripStates.value.size)
    }

    @Test
    fun clearResults_afterRipComplete_clearsState() {
        // Start rip
        ripSession.startRip(
            outputFolderUriString = "content://dummy",
            toc = dummyToc,
            metadata = dummyMetadata,
            expectedChecksums = emptyMap(),
            artworkBytes = null
        )

        // Force completion without cancelling
        val isRippingField = RipSession::class.java.getDeclaredField("_isRipping")
        isRippingField.isAccessible = true
        val stateFlow = isRippingField.get(ripSession) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        stateFlow.value = false

        // Now clear
        ripSession.clearResults()

        // Assert cleared
        assertTrue(ripSession.ripStates.value.isEmpty())
    }

    @Test
    fun cancel_setsIsRippingToFalseAndClearsState() {
        // Start rip
        ripSession.startRip(
            outputFolderUriString = "content://dummy",
            toc = dummyToc,
            metadata = dummyMetadata,
            expectedChecksums = emptyMap(),
            artworkBytes = null
        )
        assertTrue(ripSession.isRipping.value)
        assertFalse(ripSession.ripStates.value.isEmpty())

        // Cancel
        ripSession.cancel()

        // Assert isRipping is false
        assertFalse(ripSession.isRipping.value)
        assertTrue(ripSession.ripStates.value.isEmpty())
    }
}