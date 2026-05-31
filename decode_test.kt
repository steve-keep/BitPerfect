fun main() {
    val hex = "5465737420417274697374"
    val decoded = String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), Charsets.UTF_8).trim()
    println(decoded)
}
