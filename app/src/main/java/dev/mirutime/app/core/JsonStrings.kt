package dev.mirutime.app.core

// chapter_pages stores a JSON array of relative file paths. Parsing it with
// android.jar's org.json is unavailable in plain JVM unit tests, so this keeps
// the filesystem cleanup path testable and independent of Android stubs.
internal fun jsonStringArray(raw: String): List<String> {
    val body = raw.trim()
    if (!body.startsWith("[") || !body.endsWith("]")) return emptyList()
    return JsonString.findAll(body.substring(1, body.length - 1))
        .map { match ->
            match.groupValues[1]
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        .toList()
}

private val JsonString = Regex("\"((?:\\\\.|[^\"\\\\])*)\"")
