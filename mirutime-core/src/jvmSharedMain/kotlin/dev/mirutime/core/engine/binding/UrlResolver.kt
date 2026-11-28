package dev.mirutime.core.engine.binding

// Reference resolution of RFC 3986 section 5.2, behind the url_resolve global.
//
// java.net.URI.resolve follows RFC 2396 instead: a query-only reference
// ("?page=2") replaces the last path segment of the base, ".." above the root
// stays in the result, and a character that real pages put in an href (space,
// "|") makes it throw, which sent the unresolved reference back to the plugin.
internal object UrlResolver {

    // A scheme is only recognised in its RFC form: "12:30" is a relative path.
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val AUTHORITY_PATH_QUERY_FRAGMENT =
        Regex("^(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$", RegexOption.DOT_MATCHES_ALL)

    private class Parts(
        val scheme: String?,
        val authority: String?,
        val path: String,
        val query: String?,
        val fragment: String?,
    )

    fun resolve(base: String, reference: String): String {
        val b = parse(escape(base))
        val r = parse(escape(reference))
        val scheme: String?
        val authority: String?
        val path: String
        val query: String?
        if (r.scheme != null) {
            scheme = r.scheme
            authority = r.authority
            path = removeDotSegments(r.path)
            query = r.query
        } else {
            scheme = b.scheme
            if (r.authority != null) {
                authority = r.authority
                path = removeDotSegments(r.path)
                query = r.query
            } else {
                authority = b.authority
                if (r.path.isEmpty()) {
                    path = b.path
                    query = r.query ?: b.query
                } else {
                    path = removeDotSegments(if (r.path.startsWith("/")) r.path else merge(b, r.path))
                    query = r.query
                }
            }
        }
        return buildString {
            if (scheme != null) append(scheme).append(':')
            if (authority != null) append("//").append(authority)
            append(path)
            if (query != null) append('?').append(query)
            if (r.fragment != null) append('#').append(r.fragment)
        }
    }

    private fun parse(text: String): Parts {
        val scheme = SCHEME.find(text)?.groupValues?.get(1)
        val rest = if (scheme != null) text.substring(scheme.length + 1) else text
        // Every group is optional and the path group takes anything else, so the match is always
        // there; the fallback keeps a host bug from turning into an NPE.
        val m = AUTHORITY_PATH_QUERY_FRAGMENT.matchEntire(rest)
            ?: return Parts(scheme, null, rest, null, null)
        return Parts(
            scheme = scheme,
            authority = m.groups[1]?.value,
            path = m.groups[2]?.value.orEmpty(),
            query = m.groups[3]?.value,
            fragment = m.groups[4]?.value,
        )
    }

    // RFC 3986 5.2.3
    private fun merge(base: Parts, referencePath: String): String =
        if (base.authority != null && base.path.isEmpty()) {
            "/$referencePath"
        } else {
            base.path.substring(0, base.path.lastIndexOf('/') + 1) + referencePath
        }

    // RFC 3986 5.2.4
    private fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> input = input.substring(3)
                input.startsWith("./") -> input = input.substring(2)
                input.startsWith("/./") -> input = input.substring(2)
                input == "/." -> input = "/"
                input.startsWith("/../") -> {
                    input = input.substring(3)
                    dropLastSegment(output)
                }
                input == "/.." -> {
                    input = "/"
                    dropLastSegment(output)
                }
                input == "." || input == ".." -> input = ""
                else -> {
                    // Move the first segment, with its leading "/" if it has one.
                    val end = input.indexOf('/', if (input.startsWith("/")) 1 else 0)
                        .let { if (it == -1) input.length else it }
                    output.append(input, 0, end)
                    input = input.substring(end)
                }
            }
        }
        return output.toString()
    }

    private fun dropLastSegment(output: StringBuilder) {
        output.setLength(maxOf(output.lastIndexOf("/"), 0))
    }

    // ASCII characters that RFC 3986 does not allow anywhere in a reference are
    // percent-encoded (what a browser sends). Everything else, non-ASCII text and
    // existing escapes included, is left exactly as the page wrote it.
    private fun escape(text: String): String {
        if (text.none { it.code <= 0x20 || it.code == 0x7F || it in ILLEGAL }) return text
        return buildString {
            for (c in text) {
                if (c.code <= 0x20 || c.code == 0x7F || c in ILLEGAL) {
                    append('%').append("%02X".format(c.code))
                } else {
                    append(c)
                }
            }
        }
    }

    private const val ILLEGAL = "\"<>\\^`{|}"
}
