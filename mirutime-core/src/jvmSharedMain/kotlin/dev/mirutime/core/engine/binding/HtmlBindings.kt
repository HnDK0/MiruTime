package dev.mirutime.core.engine.binding

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import com.fleeksoft.ksoup.nodes.Node
import com.fleeksoft.ksoup.nodes.TextNode
import dev.mirutime.core.engine.LuaSession
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaUserdata
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction

// HTML bindings on Ksoup, matching NoveLA (LuaSourceLoader) semantics.
// html_select/html_select_first accept either a raw HTML string or an element
// and return element TABLES with a __element userdata plus a lazily evaluated
// metatable, so both dot access (`el.text`, `el.href`) and colon methods
// (`el:attr("x")`, `el:select(sel)`, `el:remove()`) behave as in NoveLA.

// Key holding the wrapped Ksoup Element inside an element table. Top-level so
// ElementTables reads it without reaching back into HtmlBindings.
internal const val ELEMENT_KEY = "__element"

object HtmlBindings {

    fun install(session: LuaSession) {
        val globals = session.globals
        // One metatable for every element table this session builds.
        val tables = ElementTables()
        // NoveLA LuaSourceLoader.kt:1025-1035: html_parse(html) → { text, html,
        // title, body } (1-arg); body is an element table, so select/text keep
        // working on `parsed.body`. Any failure → nil.
        globals.set("html_parse", object : OneArgFunction() {
            override fun call(html: LuaValue): LuaValue = try {
                val doc = Ksoup.parse(html.checkjstring(), "")
                LuaTable().also { t ->
                    // The parsed Document itself: html_select(doc, ...) and
                    // friends then reuse this tree instead of re-parsing the
                    // `html` snapshot taken below.
                    t.set(ELEMENT_KEY, LuaUserdata(doc))
                    t.set("text", LuaValue.valueOf(doc.text()))
                    t.set("html", LuaValue.valueOf(doc.html()))
                    t.set("title", LuaValue.valueOf(doc.title()))
                    t.set("body", tables.from(doc.body()))
                }
            } catch (_: Exception) {
                LuaValue.NIL
            }
        })
        globals.set("html_select", object : TwoArgFunction() {
            override fun call(el: LuaValue, sel: LuaValue): LuaValue = try {
                val selector = sel.checkjstring()
                val element = elementFromValue(el)
                val matches = if (element != null) {
                    element.select(selector)
                } else {
                    Ksoup.parse(el.checkjstring(), "").select(selector)
                }
                LuaTable().also { table ->
                    matches.forEachIndexed { i, child -> table.set(i + 1, tables.from(child)) }
                }
            } catch (_: Exception) {
                LuaTable()
            }
        })
        globals.set("html_select_first", object : TwoArgFunction() {
            override fun call(el: LuaValue, sel: LuaValue): LuaValue = try {
                val selector = sel.checkjstring()
                val element = elementFromValue(el)
                val first = if (element != null) {
                    element.selectFirst(selector)
                } else {
                    Ksoup.parse(el.checkjstring(), "").selectFirst(selector)
                }
                if (first != null) tables.from(first) else LuaValue.NIL
            } catch (_: Exception) {
                LuaValue.NIL
            }
        })
        globals.set("html_text", object : OneArgFunction() {
            override fun call(el: LuaValue): LuaValue = try {
                val element = elementFromValue(el)
                val text = if (element != null) {
                    extractText(element)
                } else {
                    extractText(Ksoup.parseBodyFragment(el.checkjstring(), "").body())
                }
                LuaValue.valueOf(text)
            } catch (_: Exception) {
                LuaValue.NIL
            }
        })
        globals.set("html_attr", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = try {
                // NoveLA shorthand: html_attr(html_or_element, selector, name).
                if (args.narg() >= 3) {
                    val source = args.arg(1)
                    val selector = args.arg(2).checkjstring()
                    val attrName = args.arg(3).checkjstring()
                    val element = elementFromValue(source)
                    val target = if (element != null) {
                        element.selectFirst(selector)
                    } else {
                        Ksoup.parse(source.checkjstring(), "").selectFirst(selector)
                    }
                    LuaValue.valueOf(target?.attr(attrName) ?: "")
                } else {
                    // MiruTime direct form: html_attr(element, name).
                    val element = elementFromValue(args.arg(1)) ?: return LuaValue.valueOf("")
                    LuaValue.valueOf(element.attr(args.arg(2).checkjstring()))
                }
            } catch (_: Exception) {
                LuaValue.valueOf("")
            }
        })
        globals.set("html_html", object : OneArgFunction() {
            override fun call(el: LuaValue): LuaValue {
                val element = elementFromValue(el) ?: return LuaValue.NIL
                return LuaValue.valueOf(element.html())
            }
        })
        globals.set("html_outerHtml", object : OneArgFunction() {
            override fun call(el: LuaValue): LuaValue {
                val element = elementFromValue(el) ?: return LuaValue.NIL
                return LuaValue.valueOf(element.outerHtml())
            }
        })
        globals.set("html_absUrl", object : TwoArgFunction() {
            override fun call(el: LuaValue, attr: LuaValue): LuaValue {
                val element = elementFromValue(el) ?: return LuaValue.valueOf("")
                val attrName = attr.checkjstring()
                // Ksoup returns "" when no base URI is known — fall back to
                // the raw attribute (NoveLA precedent).
                val absolute = element.absUrl(attrName)
                return LuaValue.valueOf(absolute.ifEmpty { element.attr(attrName) })
            }
        })
        globals.set("html_remove", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs = try {
                val arg1 = args.arg(1)
                val element = elementFromValue(arg1)
                if (element != null && args.narg() == 1) {
                    // MiruTime single-argument form: detach the element.
                    element.remove()
                    LuaValue.NIL
                } else if (element != null) {
                    // NoveLA cleaner: drop matching selectors, return html.
                    for (i in 2..args.narg()) {
                        val selector = args.arg(i).optjstring(null) ?: continue
                        if (selector.isNotBlank()) element.select(selector).remove()
                    }
                    LuaValue.valueOf(element.html())
                } else {
                    val doc = Ksoup.parse(arg1.checkjstring(), "")
                    for (i in 2..args.narg()) {
                        val selector = args.arg(i).optjstring(null) ?: continue
                        if (selector.isNotBlank()) doc.select(selector).remove()
                    }
                    LuaValue.valueOf(doc.body().html())
                }
            } catch (_: Exception) {
                args.arg(1)
            }
        })
    }

    // Lenient resolver for html_* arguments: the wrapped element first, then a
    // hand-built table's `html` string (re-parsed). NoveLA returns an empty
    // selection for such tables; the factory pipeline relies on the lenient
    // form instead.
    private fun elementFromValue(v: LuaValue): Element? {
        elementOf(v)?.let { return it }
        if (!v.istable()) return null
        val html = v.checktable().get("html")
        return if (html.isstring()) Ksoup.parse(html.tojstring(), "") else null
    }

    // ── html_text: NoveLA TextExtractor parity ───────────────────────────────
    // NoveLA's html_text (LuaSourceLoader.HtmlTextFunction -> TextExtractor)
    // separates paragraphs with "\n\n" and <br> with a single "\n"; Ksoup's own
    // text() joins every node with a space, which collapses a chapter into ONE
    // line. Corpus cleanup regexes are line-oriented (content.standardTransforms
    // strips watermark lines with ".*?\n" and credit lines with "^...$" under
    // (?m)); without separators they never match — or worse, the heading regex
    // "А[^\n\r]*" swallows the whole chapter and the text comes back empty.
    // ceiling: NoveLA also rewrites <img> to a BookTextMapper XML placeholder;
    // that reader-side path is not ported yet (see LocalBook.kt), so images
    // contribute no text here.
    private fun extractText(node: Node?): String {
        val children = node?.childNodes() ?: return ""
        if (children.isEmpty()) return ""
        return children.joinToString("") { child ->
            when (child.nodeName()) {
                "p" -> paragraphText(child)
                "br" -> "\n"
                "hr" -> "\n\n"
                // Top level: a bare text node carries no trailing separator
                // (NoveLA TextExtractor.get).
                else -> if (child is TextNode) child.text().trim() else nestedText(child)
            }
        }
    }

    // NoveLA getNodeTextTraverse: a bare text node inside a container ends its
    // own paragraph, so it gets the trailing separator.
    private fun nestedText(node: Node): String {
        val children = node.childNodes()
        if (children.isEmpty()) return ""
        return children.joinToString("") { child ->
            when (child.nodeName()) {
                "p" -> paragraphText(child)
                "br" -> "\n"
                "hr" -> "\n\n"
                else -> if (child is TextNode) {
                    val text = child.text().trim()
                    if (text.isEmpty()) "" else text + "\n\n"
                } else {
                    nestedText(child)
                }
            }
        }
    }

    // NoveLA getPTraverse: <p> content, trimmed, then a paragraph break.
    private fun paragraphText(node: Node): String {
        val inner = node.childNodes().joinToString("") { child ->
            when (child.nodeName()) {
                "br" -> "\n"
                else -> if (child is TextNode) child.text() else innerTraverse(child)
            }
        }.trim()
        return if (inner.isEmpty()) "" else inner + "\n\n"
    }

    private fun innerTraverse(node: Node): String =
        node.childNodes().joinToString("") { child ->
            when (child.nodeName()) {
                "br" -> "\n"
                else -> if (child is TextNode) child.text() else innerTraverse(child)
            }
        }
}

// One element-table factory per Lua session: every element table shares a
// single metatable, so a select() over a chapter allocates one table per
// element instead of a table + a metatable + an __index closure per element.
// Per-session rather than process-wide: this is a live LuaTable and LuaJ
// tables are not thread-safe; the saving is the same either way, the sharing
// risk is not taken.
internal class ElementTables {
    private val metatable: LuaTable =
        LuaTable().also { mt ->
            mt.set(LuaValue.INDEX, object : TwoArgFunction() {
                override fun call(table: LuaValue, key: LuaValue): LuaValue {
                    // Strict lookup: a lenient (re-parsing) resolver here would
                    // re-parse the HTML on every property read if a plugin
                    // attached this metatable to its own table from Lua.
                    val el = elementOf(table) ?: return LuaValue.NIL
                    val keyStr = key.optjstring(null) ?: return LuaValue.NIL
                    val value = when (keyStr) {
                        "text" -> LuaValue.valueOf(el.text())
                        "html" -> LuaValue.valueOf(el.html())
                        "href" -> LuaValue.valueOf(el.attr("abs:href").ifEmpty { el.attr("href") })
                        "src" -> LuaValue.valueOf(el.attr("abs:src").ifEmpty { el.attr("src") })
                        "title" -> LuaValue.valueOf(el.attr("title"))
                        "class" -> LuaValue.valueOf(el.attr("class"))
                        "id" -> LuaValue.valueOf(el.attr("id"))
                        "get_text" -> object : ZeroArgFunction() {
                            override fun call(): LuaValue = LuaValue.valueOf(el.text())
                        }
                        "get_html" -> object : ZeroArgFunction() {
                            override fun call(): LuaValue = LuaValue.valueOf(el.html())
                        }
                        "attr" -> object : VarArgFunction() {
                            override fun invoke(args: Varargs): Varargs {
                                // `el:attr("x")` prepends self — take the last arg.
                                val name = if (args.narg() >= 2) args.arg(2) else args.arg(1)
                                return try {
                                    LuaValue.valueOf(el.attr(name.checkjstring()))
                                } catch (_: Exception) {
                                    LuaValue.valueOf("")
                                }
                            }
                        }
                        "select" -> object : VarArgFunction() {
                            override fun invoke(args: Varargs): Varargs {
                                val selector = if (args.narg() >= 2) args.arg(2) else args.arg(1)
                                return try {
                                    val elems = el.select(selector.checkjstring())
                                    LuaTable().also { out ->
                                        elems.forEachIndexed { i, child -> out.set(i + 1, from(child)) }
                                    }
                                } catch (_: Exception) {
                                    LuaTable()
                                }
                            }
                        }
                        "remove" -> object : ZeroArgFunction() {
                            override fun call(): LuaValue {
                                el.remove()
                                return LuaValue.NIL
                            }
                        }
                        else -> LuaValue.NIL
                    }
                    if (value != LuaValue.NIL) table.set(key, value)
                    return value
                }
            })
        }

    // Element tables still pay one table per element, and the method closures
    // (attr/select/remove/get_text/get_html) stay per element too: dot-form
    // `el.attr("x")` has no self argument, so a shared function cannot recover
    // the element. Lifting those would break the supported dot form.
    fun from(el: Element): LuaTable = LuaTable().also {
        it.set(ELEMENT_KEY, LuaUserdata(el))
        it.setmetatable(metatable)
    }
}

// Strict resolver used by the element metatable: only the wrapped element,
// never a re-parse of a table's stored `html`.
internal fun elementOf(v: LuaValue): Element? {
    if (v.istable()) {
        val user = v.checktable().get(ELEMENT_KEY)
        return if (user.isuserdata()) user.touserdata() as? Element else null
    }
    return if (v.isuserdata()) v.touserdata() as? Element else null
}
