package dev.mirutime.core.opds

import java.net.URLEncoder

/**
 * Fills the search variable of an OPDS/OpenSearch URI template. Catalogs use
 * both the OpenSearch form (`q={searchTerms}`) and, from OPDS 2, RFC 6570
 * query expansion (`{?query}`); an expression we do not know is left as is, so
 * an unsupported template stays visible instead of resolving to a wrong URL.
 */
object OpdsSearchTemplate {
    private const val SEARCH_TERMS = "searchTerms"
    private const val QUERY = "query"

    // Any `{...}` expression; matching the whole template keeps the literal
    // braces of an unsupported one intact.
    private val EXPRESSION = Regex("\\{([^{}]*)\\}")

    /** True when [href] carries a search variable we can fill. */
    fun isTemplated(href: String): Boolean =
        EXPRESSION.findAll(href).any { variableOf(it.groupValues[1]) != null }

    fun substitute(template: String, query: String): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return EXPRESSION.replace(template) { match ->
            val variable = variableOf(match.groupValues[1]) ?: return@replace match.value
            variable.prefix?.let { "$it${variable.name}=$encoded" } ?: encoded
        }
    }

    private class Variable(val prefix: Char?, val name: String)

    private fun variableOf(expression: String): Variable? {
        val prefix = expression.firstOrNull()?.takeIf { it == '?' || it == '&' }
        val name = if (prefix == null) expression else expression.drop(1)
        return when {
            name == SEARCH_TERMS -> Variable(prefix, name)
            // A bare `{query}` is a path expansion, not the catalogue's query
            // parameter: only the RFC 6570 forms with a prefix are ours.
            name == QUERY && prefix != null -> Variable(prefix, name)
            else -> null
        }
    }
}
