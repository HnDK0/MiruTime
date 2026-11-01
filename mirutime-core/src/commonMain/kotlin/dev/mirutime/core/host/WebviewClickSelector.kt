package dev.mirutime.core.host

// Plugin-supplied `click` is untrusted: accept only a plain CSS selector or
// the single jQuery form dood emits (`$("<selector>").click()`), never JS.
// Both the Lua bridge (NetworkBindings) and the hidden WebView
// (AndroidWebviewFetcher) run this before a page is opened; the host then
// builds the click script itself from the extracted selector.
//
// shortcut: parentheses are accepted only as a non-nested functional
// pseudo-class (`:nth-child(2)`); a quoted `(` inside an attribute value is
// rejected. Upgrade if a plugin needs parentheses as attribute data.
object WebviewClickSelector {
    const val MAX_LENGTH = 200

    // Allowed CSS selector characters. `$`, backtick, `;`, `\`, newline and
    // NUL are the JS-injection surface and are absent on purpose.
    private val SAFE = Regex("""^[A-Za-z0-9_\-.#>+~*:\[\]="'() ]+$""")
    private val SAFE_WITHOUT_PARENS = Regex("""^[A-Za-z0-9_\-.#>+~*:\[\]="' ]+$""")
    private val FUNCTIONAL_PSEUDO = Regex(""":[A-Za-z-]+\([^()]*\)""")
    private val JQUERY_CLICK = Regex(
        """^\$\s*\(\s*(?:"([^"]*)"|'([^']*)')\s*\)\s*\.\s*click\s*\(\s*\)$""",
    )

    fun extract(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val match = JQUERY_CLICK.matchEntire(trimmed)
        val selector = match?.groupValues?.let { it[1].ifEmpty { it[2] } } ?: trimmed
        return selector.takeIf(::isValid)
    }

    private fun isValid(selector: String): Boolean {
        if (selector.isBlank() || selector.length > MAX_LENGTH) return false
        if (!SAFE.matches(selector)) return false
        if (!quotesOnlyInAttributes(selector)) return false
        // A bare `name(...)` is a JS call, not CSS: after removing the only
        // legal parenthesised form no parentheses may remain.
        val bare = FUNCTIONAL_PSEUDO.replace(selector, "")
        return bare.none { it == '(' || it == ')' } && SAFE_WITHOUT_PARENS.matches(bare)
    }

    // A quote or `=` outside `[...]` is JS text (`window.location='x'`), not
    // CSS: attribute values are the only place those characters may appear.
    private fun quotesOnlyInAttributes(selector: String): Boolean {
        var inAttribute = false
        var quote: Char? = null
        for (c in selector) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '[' -> {
                    if (inAttribute) return false
                    inAttribute = true
                }
                c == ']' -> {
                    if (!inAttribute) return false
                    inAttribute = false
                }
                c == '"' || c == '\'' -> {
                    if (!inAttribute) return false
                    quote = c
                }
                c == '=' && !inAttribute -> return false
            }
        }
        return !inAttribute && quote == null
    }
}
