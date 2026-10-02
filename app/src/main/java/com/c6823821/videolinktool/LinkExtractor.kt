package com.c6823821.videolinktool

object LinkExtractor {
    private val urlRegex = Regex("""https?://[^\s"'<>]+""")

    fun extract(raw: String): String? {
        val found = urlRegex.find(raw)?.value ?: return null
        return found.trimEnd('.', ',', ';', '，', '。', '；', ')', '）', ']', '】', '\n', '\r')
    }

    fun host(url: String): String = runCatching {
        java.net.URI(url).host?.lowercase().orEmpty()
    }.getOrDefault("")

    fun sanitizeTitle(title: String?): String {
        val value = title.orEmpty()
            .replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), "_")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd('.')
        return value.take(80).ifBlank { "未命名" }
    }
}
