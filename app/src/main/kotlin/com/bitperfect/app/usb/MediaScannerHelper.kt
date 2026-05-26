package com.bitperfect.app.usb

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.bitperfect.core.utils.AppLogger
import java.net.URLDecoder

object MediaScannerHelper {
    fun scanSafUri(context: Context, uri: Uri) {
        try {
            val decodedUri = URLDecoder.decode(uri.toString(), "UTF-8")
            val documentPart = decodedUri.substringAfter("/document/", "")
            if (documentPart.isEmpty()) return

            val parts = documentPart.split(":")
            if (parts.size != 2) return

            val volume = parts[0]
            val path = parts[1]

            val absolutePath = if (volume.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0/$path"
            } else {
                "/storage/$volume/$path"
            }

            AppLogger.d("MediaScannerHelper", "Scanning path: $absolutePath")
            MediaScannerConnection.scanFile(context, arrayOf(absolutePath), null) { scannedPath, scannedUri ->
                AppLogger.d("MediaScannerHelper", "Scanned $scannedPath -> $scannedUri")
            }
        } catch (e: Exception) {
            AppLogger.e("MediaScannerHelper", "Failed to parse and scan URI: $uri", e)
        }
    }
}
