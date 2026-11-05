package dev.mirutime.core.reader.html

/**
 * Named HTML character references (HTML 4.01 set plus `apos` and the common
 * HTML5 spacing/zero-width names). Lookup is case-sensitive, as in HTML.
 *
 * Unknown names are handled by the caller (dropped, never spoken literally).
 */
internal object HtmlEntities {
    private val table: Map<String, String> = buildMap {
        // Latin-1
        put("nbsp", "\u00A0"); put("iexcl", "¡"); put("cent", "¢"); put("pound", "£")
        put("curren", "¤"); put("yen", "¥"); put("brvbar", "¦"); put("sect", "§")
        put("uml", "¨"); put("copy", "©"); put("ordf", "ª"); put("laquo", "«")
        put("not", "¬"); put("shy", ""); put("reg", "®"); put("macr", "¯")
        put("deg", "°"); put("plusmn", "±"); put("sup2", "²"); put("sup3", "³")
        put("acute", "´"); put("micro", "µ"); put("para", "¶"); put("middot", "·")
        put("cedil", "¸"); put("sup1", "¹"); put("ordm", "º"); put("raquo", "»")
        put("frac14", "¼"); put("frac12", "½"); put("frac34", "¾"); put("iquest", "¿")
        put("Agrave", "À"); put("Aacute", "Á"); put("Acirc", "Â"); put("Atilde", "Ã")
        put("Auml", "Ä"); put("Aring", "Å"); put("AElig", "Æ"); put("Ccedil", "Ç")
        put("Egrave", "È"); put("Eacute", "É"); put("Ecirc", "Ê"); put("Euml", "Ë")
        put("Igrave", "Ì"); put("Iacute", "Í"); put("Icirc", "Î"); put("Iuml", "Ï")
        put("ETH", "Ð"); put("Ntilde", "Ñ"); put("Ograve", "Ò"); put("Oacute", "Ó")
        put("Ocirc", "Ô"); put("Otilde", "Õ"); put("Ouml", "Ö"); put("times", "×")
        put("Oslash", "Ø"); put("Ugrave", "Ù"); put("Uacute", "Ú"); put("Ucirc", "Û")
        put("Uuml", "Ü"); put("Yacute", "Ý"); put("THORN", "Þ"); put("szlig", "ß")
        put("agrave", "à"); put("aacute", "á"); put("acirc", "â"); put("atilde", "ã")
        put("auml", "ä"); put("aring", "å"); put("aelig", "æ"); put("ccedil", "ç")
        put("egrave", "è"); put("eacute", "é"); put("ecirc", "ê"); put("euml", "ë")
        put("igrave", "ì"); put("iacute", "í"); put("icirc", "î"); put("iuml", "ï")
        put("eth", "ð"); put("ntilde", "ñ"); put("ograve", "ò"); put("oacute", "ó")
        put("ocirc", "ô"); put("otilde", "õ"); put("ouml", "ö"); put("divide", "÷")
        put("oslash", "ø"); put("ugrave", "ù"); put("uacute", "ú"); put("ucirc", "û")
        put("uuml", "ü"); put("yacute", "ý"); put("thorn", "þ"); put("yuml", "ÿ")
        // Symbols, Greek, math, arrows
        put("fnof", "ƒ"); put("Alpha", "Α"); put("Beta", "Β"); put("Gamma", "Γ")
        put("Delta", "Δ"); put("Epsilon", "Ε"); put("Zeta", "Ζ"); put("Eta", "Η")
        put("Theta", "Θ"); put("Iota", "Ι"); put("Kappa", "Κ"); put("Lambda", "Λ")
        put("Mu", "Μ"); put("Nu", "Ν"); put("Xi", "Ξ"); put("Omicron", "Ο")
        put("Pi", "Π"); put("Rho", "Ρ"); put("Sigma", "Σ"); put("Tau", "Τ")
        put("Upsilon", "Υ"); put("Phi", "Φ"); put("Chi", "Χ"); put("Psi", "Ψ")
        put("Omega", "Ω"); put("alpha", "α"); put("beta", "β"); put("gamma", "γ")
        put("delta", "δ"); put("epsilon", "ε"); put("zeta", "ζ"); put("eta", "η")
        put("theta", "θ"); put("iota", "ι"); put("kappa", "κ"); put("lambda", "λ")
        put("mu", "μ"); put("nu", "ν"); put("xi", "ξ"); put("omicron", "ο")
        put("pi", "π"); put("rho", "ρ"); put("sigmaf", "ς"); put("sigma", "σ")
        put("tau", "τ"); put("upsilon", "υ"); put("phi", "φ"); put("chi", "χ")
        put("psi", "ψ"); put("omega", "ω"); put("thetasym", "ϑ"); put("upsih", "ϒ")
        put("piv", "ϖ"); put("bull", "•"); put("hellip", "…"); put("prime", "′")
        put("Prime", "″"); put("oline", "‾"); put("frasl", "⁄"); put("weierp", "℘")
        put("image", "ℑ"); put("real", "ℜ"); put("trade", "™"); put("alefsym", "ℵ")
        put("larr", "←"); put("uarr", "↑"); put("rarr", "→"); put("darr", "↓")
        put("harr", "↔"); put("crarr", "↵"); put("lArr", "⇐"); put("uArr", "⇑")
        put("rArr", "⇒"); put("dArr", "⇓"); put("hArr", "⇔"); put("forall", "∀")
        put("part", "∂"); put("exist", "∃"); put("empty", "∅"); put("nabla", "∇")
        put("isin", "∈"); put("notin", "∉"); put("ni", "∋"); put("prod", "∏")
        put("sum", "∑"); put("minus", "−"); put("lowast", "∗"); put("radic", "√")
        put("prop", "∝"); put("infin", "∞"); put("ang", "∠"); put("and", "∧")
        put("or", "∨"); put("cap", "∩"); put("cup", "∪"); put("int", "∫")
        put("there4", "∴"); put("sim", "∼"); put("cong", "≅"); put("asymp", "≈")
        put("ne", "≠"); put("equiv", "≡"); put("le", "≤"); put("ge", "≥")
        put("sub", "⊂"); put("sup", "⊃"); put("nsub", "⊄"); put("sube", "⊆")
        put("supe", "⊇"); put("oplus", "⊕"); put("otimes", "⊗"); put("perp", "⊥")
        put("sdot", "⋅"); put("lceil", "⌈"); put("rceil", "⌉"); put("lfloor", "⌊")
        put("rfloor", "⌋"); put("lang", "〈"); put("rang", "〉"); put("loz", "◊")
        put("spades", "♠"); put("clubs", "♣"); put("hearts", "♥"); put("diams", "♦")
        put("quot", "\""); put("amp", "&"); put("lt", "<"); put("gt", ">")
        // HTML 4.01 also defines these uppercase variants.
        put("QUOT", "\""); put("AMP", "&"); put("LT", "<"); put("GT", ">")
        put("COPY", "©"); put("REG", "®")
        put("apos", "'"); put("OElig", "Œ"); put("oelig", "œ"); put("Scaron", "Š")
        put("scaron", "š"); put("Yuml", "Ÿ"); put("circ", "ˆ"); put("tilde", "˜")
        put("ensp", " "); put("emsp", " "); put("thinsp", " "); put("zwnj", "")
        put("zwj", ""); put("lrm", ""); put("rlm", ""); put("ndash", "–")
        put("mdash", "—"); put("lsquo", "‘"); put("rsquo", "’"); put("sbquo", "‚")
        put("ldquo", "“"); put("rdquo", "”"); put("bdquo", "„"); put("dagger", "†")
        put("Dagger", "‡"); put("permil", "‰"); put("lsaquo", "‹"); put("rsaquo", "›")
        put("euro", "€"); put("NoBreak", ""); put("ZeroWidthSpace", "")
    }

    fun decode(name: String): String? = table[name]
}
