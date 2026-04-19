package com.bitperfect.core.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DriveOffsetServiceTest {

    private lateinit var context: Context
    private lateinit var service: DriveOffsetService

    private val mockHtml = """
        <html>
        <body>
        <table>
        <tr>
        <td bgcolor="#000000"><font face="Arial" size="2" color="#FFFFFF"><b>CD Drive</b></font></td>
        <td bgcolor="#000000" align="center"><font face="Arial" size="2" color="#FFFFFF"><b>Correction Offset</b></font></td>
        <td bgcolor="#000000" align="center"><font face="Arial" size="2" color="#FFFFFF"><b>Submitted By</b></font></td>
        <td bgcolor="#000000" align="center"><font face="Arial" size="2" color="#FFFFFF"><b>Percentage Agree</b></font></td>
        </tr>
        <tr>
        <td bgcolor="#F4F4F4"><font face="Arial" size="2">ASUS - DRW-24B1ST   a</font></td>
        <td align="center" bgcolor="#F4F4F4"><font face="Arial" size="2">+6</font></td>
        <td align="center" bgcolor="#F4F4F4"><font face="Arial" size="2">10</font></td>
        <td align="center" bgcolor="#F4F4F4"><font face="Arial" size="2">100%</font></td>
        </tr>
        <tr>
        <td bgcolor="#F4F4F4"><font face="Arial" size="2">Lite-ON - iHAS124   W</font></td>
        <td align="center" bgcolor="#F4F4F4"><font face="Arial" size="2">-6</font></td>
        <td align="center" bgcolor="#F4F4F4"><font face="Arial" size="2">10</font></td>
        <td align="center" bgcolor="#F4F4F4"><font face="Arial" size="2">100%</font></td>
        </tr>
        </table>
        </body>
        </html>
    """.trimIndent()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val mockEngine = MockEngine { request ->
            respond(
                content = mockHtml,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/html")
            )
        }
        val client = HttpClient(mockEngine)
        service = DriveOffsetService(context, client)
    }

    @Test
    fun `test findOffsetForDrive exact match`() = runBlocking {
        val offset = service.findOffsetForDrive("ASUS", "DRW-24B1ST   a")
        assertEquals(6, offset)
    }

    @Test
    fun `test findOffsetForDrive manufacturer mapping JLMS to Lite-ON`() = runBlocking {
        // JLMS should be mapped to Lite-ON
        val offset = service.findOffsetForDrive("JLMS", "iHAS124   W")
        assertEquals(-6, offset)
    }

    @Test
    fun `test findOffsetForDrive no match returns null`() = runBlocking {
        val offset = service.findOffsetForDrive("Unknown", "Drive")
        assertNull(offset)
    }
}
