package dev.mirutime.app.core

import dev.mirutime.core.api.PluginError

// A catalogue/search call failed with a typed plugin error. The finder UI
// catches this to show the real reason instead of an empty "Nothing found"
// page; the message is never logged verbatim (it may carry plugin text).
// source/operation travel with the error so the detail can name what failed.
class PluginRequestException(
    val error: PluginError,
    val sourceId: String? = null,
    val sourceName: String? = null,
    val operation: String? = null,
) : Exception(error.toString()) {
    fun toReport(identity: String? = sourceId): PluginErrorReport =
        buildPluginErrorReport(
            error = error,
            sourceId = sourceId,
            sourceName = sourceName,
            operation = operation,
            identity = identity,
        )
}
