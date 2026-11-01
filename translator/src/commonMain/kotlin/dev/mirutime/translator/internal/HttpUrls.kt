package dev.mirutime.translator.internal

import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.formUrlEncode

internal fun urlWithQuery(base: String, params: List<Pair<String, String>>): String =
    URLBuilder(base).apply {
        for ((name, value) in params) parameters.append(name, value)
    }.buildString()

internal fun formUrlEncode(params: List<Pair<String, String>>): String =
    Parameters.build {
        for ((name, value) in params) append(name, value)
    }.formUrlEncode()
