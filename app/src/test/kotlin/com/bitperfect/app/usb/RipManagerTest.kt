package com.bitperfect.app.usb

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.bitperfect.core.models.DiscToc
import com.bitperfect.core.models.DiscMetadata
import io.mockk.mockk
import io.mockk.verify
import io.mockk.every
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class RipManagerTest {

    @Test
    fun `cancel sets isCancelled to true`() {
        val context: Context = mockk(relaxed = true)
        val toc: DiscToc = mockk(relaxed = true)
        val metadata: DiscMetadata = mockk(relaxed = true)
        every { toc.tracks } returns emptyList()

        val ripManager = RipManager(
            context = context,
            outputFolderUriString = "content://dummy",
            toc = toc,
            metadata = metadata,
            expectedChecksums = emptyList(),
            artworkBytes = null,
            driveVendor = "TestVendor",
            driveProduct = "TestProduct",
            initialTracks = emptyList()
        )

        ripManager.cancel()
        assertTrue(ripManager.isCancelled)
    }

    @Test
    fun `deleteRipFiles deletes album directory`() {
        val context: Context = mockk(relaxed = true)
        val toc: DiscToc = mockk(relaxed = true)
        val metadata: DiscMetadata = mockk(relaxed = true)
        every { toc.tracks } returns emptyList()

        val ripManager = RipManager(
            context = context,
            outputFolderUriString = "content://dummy",
            toc = toc,
            metadata = metadata,
            expectedChecksums = emptyList(),
            artworkBytes = null,
            driveVendor = "TestVendor",
            driveProduct = "TestProduct",
            initialTracks = emptyList()
        )

        // Reflection to set albumDir
        val albumDir: DocumentFile = mockk()
        every { albumDir.delete() } returns true

        val field = RipManager::class.java.getDeclaredField("albumDir")
        field.isAccessible = true
        field.set(ripManager, albumDir)

        ripManager.deleteRipFiles()

        verify { albumDir.delete() }
    }

    @Test
    fun `deleteRipFiles handles exceptions gracefully`() {
        val context: Context = mockk(relaxed = true)
        val toc: DiscToc = mockk(relaxed = true)
        val metadata: DiscMetadata = mockk(relaxed = true)
        every { toc.tracks } returns emptyList()

        val ripManager = RipManager(
            context = context,
            outputFolderUriString = "content://dummy",
            toc = toc,
            metadata = metadata,
            expectedChecksums = emptyList(),
            artworkBytes = null,
            driveVendor = "TestVendor",
            driveProduct = "TestProduct",
            initialTracks = emptyList()
        )

        // Reflection to set albumDir
        val albumDir: DocumentFile = mockk()
        every { albumDir.delete() } throws IOException("Mock exception")

        val field = RipManager::class.java.getDeclaredField("albumDir")
        field.isAccessible = true
        field.set(ripManager, albumDir)

        // This should not throw an exception
        ripManager.deleteRipFiles()

        verify { albumDir.delete() }
    }
}
