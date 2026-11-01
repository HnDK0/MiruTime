package dev.mirutime.core.installer

// Literal require_lib("id") / require_lib('id') calls only. A dynamic
// require_lib(name) is intentionally invisible: resolving it would mean
// guessing a dependency, so the installer scans what the script can prove.
private val REQUIRE_LIB_PATTERN = Regex("""require_lib\s*\(\s*(['"])([^'"]+)\1""")

/** Library ids in first-appearance order (deterministic, duplicates dropped). */
fun scanRequireLibs(code: String): List<String> =
    REQUIRE_LIB_PATTERN.findAll(code)
        .map { it.groupValues[2].trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

/**
 * Resolved dependency closure of [rootCodes].
 *
 * [order] is dependency-first (a library lands before its consumers) and
 * deterministic: roots in input order, each root's requires in source order.
 * [missing] lists ids [sourceFor] could not resolve; [cycles] lists the
 * offending paths (`a -> b -> a`).
 */
data class RequireLibClosure(
    val order: List<String>,
    val missing: List<String>,
    val cycles: List<List<String>>,
)

suspend fun resolveRequireLibClosure(
    rootCodes: List<String>,
    sourceFor: suspend (String) -> String?,
): RequireLibClosure {
    val order = LinkedHashSet<String>()
    val missing = LinkedHashSet<String>()
    val cycles = mutableListOf<List<String>>()
    // 1 = on the current path, 2 = fully resolved.
    val state = HashMap<String, Int>()
    val path = ArrayList<String>()

    suspend fun visit(id: String) {
        when (state[id]) {
            2 -> return
            1 -> {
                val start = path.indexOf(id)
                if (start >= 0) cycles += path.subList(start, path.size).toList() + id
                return
            }
        }
        val code = sourceFor(id)
        if (code == null) {
            missing += id
            state[id] = 2
            return
        }
        state[id] = 1
        path += id
        for (child in scanRequireLibs(code)) visit(child)
        path.removeAt(path.lastIndex)
        state[id] = 2
        order += id
    }

    for (root in rootCodes) {
        for (id in scanRequireLibs(root)) visit(id)
    }
    return RequireLibClosure(
        order = order.toList(),
        missing = missing.toList(),
        cycles = cycles.toList(),
    )
}
