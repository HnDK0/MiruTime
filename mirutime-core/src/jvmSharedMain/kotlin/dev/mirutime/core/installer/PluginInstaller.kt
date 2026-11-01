package dev.mirutime.core.installer

import dev.mirutime.core.api.SUPPORTED_PLUGIN_API
import dev.mirutime.core.engine.createSandboxGlobals
import dev.mirutime.core.host.FileSystem
import dev.mirutime.core.network.cache.sha256Hex
import dev.mirutime.core.network.security.SsrfGuard
import dev.mirutime.core.network.security.SsrfVerdict
import kotlinx.coroutines.CancellationException

// Result of the download + sha + syntax phase, before any plugin file write.
// The graph reuses it to scan require_lib from the exact bytes it will commit.
sealed interface SourceFetch {
    data class Success(
        val bytes: ByteArray,
        val sha256: String?,
        val metadata: ScriptMetadata,
        val code: String,
    ) : SourceFetch

    data class Failed(val reason: String) : SourceFetch
}

sealed class InstallResult {
    data class Success(
        val path: String,
        val sha256: String?,
        // Parsed from the script header for plugin sources; null for shared
        // libraries, which carry no plugin metadata.
        val metadata: ScriptMetadata? = null,
    ) : InstallResult()

    data class Failed(val reason: String) : InstallResult()
}

// Typed reasons for a local (.lua picked from SAF) install; the UI maps these
// to localized messages, so no English string parsing is involved.
enum class LocalPluginFailure { EMPTY, TOO_LARGE, INVALID_UTF8, INVALID_ID, SYNTAX }

// Result of validating a local script BEFORE anything is written.
sealed interface LocalPluginInspection {
    data class Valid(
        val id: String,
        val name: String,
        val metadata: ScriptMetadata,
    ) : LocalPluginInspection

    data class Invalid(
        val reason: LocalPluginFailure,
        val detail: String,
    ) : LocalPluginInspection
}

// Path-safe plugin ids. Real catalog ids include dotted and mixed-case slugs
// ("mvlempyr.com", "NovelArrow"); any ".." is rejected (NoveLA precedent
// LuaSourceLoader.isValidExtensionId).
object PluginIds {
    private val PATTERN = Regex("[A-Za-z0-9_.-]+")

    fun isValid(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && !id.contains("..") && PATTERN.matches(id)

    /**
     * Canonical id for a local file: the script's own `id` global when valid,
     * otherwise the .lua file stem. Null means neither is a path-safe id.
     */
    fun detect(fileName: String, declaredId: String?): String? {
        val declared = declaredId?.trim()
        if (!declared.isNullOrEmpty()) return declared.takeIf(::isValid)
        // A display name must not smuggle a path segment in either separator.
        if (fileName.contains('/') || fileName.contains('\\')) return null
        val stem = (if (fileName.endsWith(".lua", ignoreCase = true)) fileName.dropLast(4) else fileName).trim()
        return stem.takeIf(::isValid)
    }
}

// Sha-verified install pipeline (external-sources index.yaml):
// download -> sha256 verify -> syntax validation -> test load -> atomic
// commit. Every check runs BEFORE any write, so a failed install leaves the
// previously installed file untouched (rollback = never touched it).
// Sources land in [installDir] and carry the metadata parsed from their
// header; the index `libraries:` entries land in [libDir] (the path
// require_lib resolves) through installLibrary/installLibraries.
// DI: FileSystem + a suspend fetcher for URLs (same bridging pattern as
// ScriptLoader — tests inject a fake "http").
// ceiling: full plugin EXECUTION happens at registry install, where
// libs/bindings are present; the header run in ScriptMetadataParser is
// sandboxed and tolerates a later throw, so a broken chunk is caught here
// without the engine bindings.
class PluginInstaller(
    private val fileSystem: FileSystem,
    private val fetcher: suspend (String) -> ByteArray,
    private val installDir: String = "lua_plugins",
    // require_lib resolves lua_libs (LuaPluginSourceFactory.libDir default) —
    // installed index libraries must land here to be reachable.
    private val libDir: String = "lua_libs",
    // Guarded by default (same default as LuaPluginSourceFactory); pass null
    // to disable — an index URL is remote-controlled, so it is validated
    // before ANY fetch. URL-only: the engine's SafeDns hook owns address checks.
    private val ssrfGuard: SsrfGuard? = SsrfGuard(),
) {
    // Download + gates without writing anything. Callers that must inspect the
    // script first (require_lib closure) commit the same bytes afterwards.
    suspend fun fetchSource(entry: IndexEntry): SourceFetch {
        preflightFailure(entry)?.let { return SourceFetch.Failed(it) }
        val expected = entry.sha256
        return when (val fetched = if (expected != null) fetchVerified(entry, expected) else fetchUnverified(entry)) {
            is Fetch.Err -> SourceFetch.Failed(fetched.reason)
            is Fetch.Ok -> {
                val code = fetched.bytes.decodeToString()
                syntaxError(code, entry.id)?.let { return SourceFetch.Failed(it) }
                // Attach the script's own metadata so a real external-sources
                // entry can be installed and loaded without host hardcoding.
                SourceFetch.Success(
                    bytes = fetched.bytes,
                    sha256 = fetched.sha256,
                    metadata = ScriptMetadataParser.parse(code),
                    code = code,
                )
            }
        }
    }

    suspend fun install(entry: IndexEntry): InstallResult =
        when (val source = fetchSource(entry)) {
            is SourceFetch.Failed -> InstallResult.Failed(source.reason)
            is SourceFetch.Success -> commitSource(entry, source)
        }

    // Atomic commit of an already-fetched source (temp file + rename): a crash
    // mid-write never leaves a half script at the final path.
    fun commitSource(entry: IndexEntry, source: SourceFetch.Success): InstallResult = try {
        val path = "$installDir/${entry.id}.lua"
        commit(path, source.bytes)
        InstallResult.Success(path, source.sha256, source.metadata)
    } catch (e: Exception) {
        fileSystem.delete("$installDir/${entry.id}.lua.tmp")
        InstallResult.Failed("write failed for '${entry.id}': ${e.message}")
    }

    // Installs one shared library from the index `libraries:` section into
    // [libDir] so a plugin's require_lib(id) resolves it. Library ids obey the
    // require_lib id rule ([a-z0-9_]+) — stricter than plugin ids.
    suspend fun installLibrary(entry: IndexEntry): InstallResult {
        if (!LIB_ID_PATTERN.matches(entry.id) || entry.id == "." || entry.id == "..") {
            return InstallResult.Failed(
                "invalid library id '${entry.id}': must match ${LIB_ID_PATTERN.pattern}",
            )
        }
        val expected = entry.sha256
            ?: return InstallResult.Failed("library '${entry.id}' has no sha256")
        // Same URL-level preflight as source install, before the sha shortcut:
        // a hostile index URL is refused even when the local copy matches.
        ssrfRefusal(entry.url)?.let { return InstallResult.Failed(it) }
        // Already at the published content: no network, no rewrite. Refresh
        // passes rescan the installed closure, so unchanged libs stay cheap.
        if (expected.equals(installedLibrarySha256(entry.id), ignoreCase = true)) {
            return InstallResult.Success("$libDir/${entry.id}.lua", expected)
        }
        return when (val fetched = fetchVerified(entry, expected)) {
            is Fetch.Err -> InstallResult.Failed(fetched.reason)
            is Fetch.Ok -> {
                syntaxError(fetched.bytes.decodeToString(), entry.id)?.let {
                    return InstallResult.Failed(it)
                }
                val path = "$libDir/${entry.id}.lua"
                commit(path, fetched.bytes)
                InstallResult.Success(path, fetched.sha256)
            }
        }
    }

    // Installs exactly the given libraries (a resolved require_lib closure).
    suspend fun installLibraries(libraries: List<IndexEntry>): List<InstallResult> =
        libraries.map { installLibrary(it) }

    /**
     * Validates a .lua picked from SAF without touching the disk: size cap,
     * strict UTF-8, path-safe id (script `id` or file stem) and Lua syntax.
     * The caller decides about overwrites (duplicate id) after this.
     */
    fun inspectLocal(fileName: String, bytes: ByteArray): LocalPluginInspection {
        if (bytes.isEmpty()) {
            return LocalPluginInspection.Invalid(LocalPluginFailure.EMPTY, "file is empty")
        }
        if (bytes.size > MAX_LOCAL_BYTES) {
            return LocalPluginInspection.Invalid(
                LocalPluginFailure.TOO_LARGE,
                "file is ${bytes.size} bytes, limit is $MAX_LOCAL_BYTES",
            )
        }
        val code = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (e: Exception) {
            return LocalPluginInspection.Invalid(
                LocalPluginFailure.INVALID_UTF8,
                e.message ?: "file is not valid UTF-8",
            )
        }
        val metadata = try {
            ScriptMetadataParser.parse(code)
        } catch (e: Exception) {
            // Parsing tolerates a later throw, but a broken sandbox must not
            // take the install flow down.
            return LocalPluginInspection.Invalid(
                LocalPluginFailure.SYNTAX,
                e.message ?: "could not read script metadata",
            )
        }
        val id = PluginIds.detect(fileName, metadata.id)
            ?: return LocalPluginInspection.Invalid(
                LocalPluginFailure.INVALID_ID,
                "id '${metadata.id ?: fileName}' is not a path-safe plugin id",
            )
        validateScript(code, id)?.let {
            return LocalPluginInspection.Invalid(LocalPluginFailure.SYNTAX, it)
        }
        return LocalPluginInspection.Valid(
            id = id,
            name = metadata.name?.trim()?.takeIf { it.isNotEmpty() } ?: id,
            metadata = metadata,
        )
    }

    /** Atomic commit of an already-validated local script. */
    fun commitLocal(inspection: LocalPluginInspection.Valid, bytes: ByteArray): InstallResult {
        val path = "$installDir/${inspection.id}.lua"
        return try {
            commit(path, bytes)
            InstallResult.Success(path, sha256Hex(bytes), inspection.metadata)
        } catch (e: Exception) {
            fileSystem.delete("$path.tmp")
            InstallResult.Failed("write failed for '${inspection.id}': ${e.message}")
        }
    }

    /** Compiles a script without calling it; null = valid, else the reason. */
    fun validateScript(code: String, id: String): String? = syntaxError(code, id)

    // sha256 of the installed script file, null when not installed.
    fun installedSha256(id: String): String? =
        fileSystem.read("$installDir/$id.lua")?.let { sha256Hex(it) }

    // sha256 of the installed shared library, null when not installed.
    fun installedLibrarySha256(id: String): String? =
        fileSystem.read("$libDir/$id.lua")?.let { sha256Hex(it) }

    // Update decision by sha256 only: the catalog
    // hash differs from the local file's hash. Versions ("1.7.1") play no
    // part — string versions cannot be reliably ordered.
    fun isUpdateAvailable(entry: IndexEntry): Boolean {
        val catalog = entry.sha256 ?: return false
        val local = installedSha256(entry.id) ?: return true
        return !local.equals(catalog, ignoreCase = true)
    }

    // The id is a remote-controlled filesystem path segment: reject anything
    // but a path-safe token before it reaches the disk (LibraryLoader.ID_PATTERN
    // precedent; ids like "../../evil" would otherwise write outside
    // installDir). Real catalog ids include dotted and mixed-case slugs
    // ("mvlempyr.com", "NovelArrow"). NoveLA precedent
    // (LuaSourceLoader.kt:1409-1410 isValidExtensionId): any ".." is rejected
    // even though single dots stay legal.
    private fun preflightFailure(entry: IndexEntry): String? {
        if (!PluginIds.isValid(entry.id)) {
            return "invalid id '${entry.id}': must match [A-Za-z0-9_.-]+"
        }
        // Api gate mirrors the registry load gate: an entry published against
        // an incompatible API never reaches the network (null = index field
        // absent, unconstrained here — metadata.api still gates at load).
        if (entry.api != null && entry.api != SUPPORTED_PLUGIN_API) {
            return "unsupported api ${entry.api} for '${entry.id}': " +
                "library supports $SUPPORTED_PLUGIN_API"
        }
        // The index URL is remote-controlled: refuse hostile targets before
        // the fetcher ever sees them (mirrors the api gate above — no network
        // for entries that cannot install).
        return ssrfRefusal(entry.url)
    }

    // SSRF verdict for an index URL, null when the fetch may proceed. URL-only:
    // a hostname is allowed through and the engine's SafeDns hook validates
    // the addresses. A refusal becomes an ordinary Failed (same contract as the
    // id/api gates): a failed install never touches the installed file.
    private fun ssrfRefusal(url: String): String? {
        val guard = ssrfGuard ?: return null
        return when (val verdict = guard.isSafe(url)) {
            is SsrfVerdict.Blocked -> "SSRF blocked for '$url': ${verdict.reason}"
            // Not produced by the URL-only guard; kept for the sealed contract.
            SsrfVerdict.DnsFailure -> "DNS resolution failed for '$url'"
            SsrfVerdict.Ok -> null
        }
    }

    // Download + sha256 check shared by sources and libraries; cancellation is
    // never converted into an ordinary Failed result.
    private suspend fun fetchVerified(entry: IndexEntry, expected: String): Fetch {
        val bytes = try {
            fetcher(entry.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetch.Err("download failed for '${entry.id}': ${e.message}")
        }
        val actual = sha256Hex(bytes)
        if (!actual.equals(expected, ignoreCase = true)) {
            return Fetch.Err("sha256 mismatch for '${entry.id}': expected $expected, got $actual")
        }
        return Fetch.Ok(bytes, actual)
    }

    // Download without sha256 verification: syntax check + atomic commit only.
    // Used when the index entry lacks a sha256 (real indexes often omit it).
    private suspend fun fetchUnverified(entry: IndexEntry): Fetch {
        val bytes = try {
            fetcher(entry.url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Fetch.Err("download failed for '${entry.id}': ${e.message}")
        }
        return Fetch.Ok(bytes, null)
    }

    // Compile + load (never call) in a fresh sandbox: broken syntax is caught
    // without executing plugin logic. null = ok, otherwise the failure reason.
    private fun syntaxError(code: String, id: String): String? = try {
        createSandboxGlobals().load(code, "@$id")
        null
    } catch (e: Exception) {
        "script '$id' failed to load: ${e.message}"
    }

    // Atomic commit: temp file + rename (ScriptLoader pattern).
    private fun commit(path: String, bytes: ByteArray) {
        fileSystem.write("$path.tmp", bytes)
        fileSystem.rename("$path.tmp", path)
    }

    private sealed interface Fetch {
        data class Ok(val bytes: ByteArray, val sha256: String?) : Fetch
        data class Err(val reason: String) : Fetch
    }

    private companion object {
        // require_lib id rule (LibraryLoader.ID_PATTERN): a library id must be
        // a lowercase path-safe token, stricter than plugin ids.
        val LIB_ID_PATTERN = Regex("[a-z0-9_]+")
    }
}

// A hand-picked plugin script is small; this keeps a bogus file from
// allocating before the UTF-8/syntax checks run.
const val MAX_LOCAL_BYTES = 2 * 1024 * 1024
