package dev.mirutime.core.engine

// A UTF-8 byte order mark decodes to U+FEFF. LuaJ's lexer rejects it ("unexpected
// symbol 239") and kotlinx.serialization does not count it as whitespace. Windows
// editors add one to files saved as "UTF-8 with BOM", and some servers prepend it
// to JSON, so text that reaches the engine drops a leading mark first.
internal fun String.withoutBom(): String =
    if (isNotEmpty() && this[0] == '﻿') substring(1) else this
