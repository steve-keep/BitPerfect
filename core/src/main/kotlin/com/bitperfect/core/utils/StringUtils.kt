package com.bitperfect.core.utils

/**
 * Decodes literal Unicode escape sequences (e.g., "\u00e9") present in a string into
 * their corresponding characters (e.g., 'é').
 */
fun String.decodeUnicodeEscapes(): String {
    val regex = Regex("\\\\u([0-9a-fA-F]{4})")
    return regex.replace(this) { matchResult ->
        val hexValue = matchResult.groupValues[1]
        hexValue.toInt(16).toChar().toString()
    }
}
