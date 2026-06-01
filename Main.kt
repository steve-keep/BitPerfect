fun main() {
    val hex = "5465737420417274697374"
    try {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        println(bytes.toString(Charsets.UTF_8).trim())
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
