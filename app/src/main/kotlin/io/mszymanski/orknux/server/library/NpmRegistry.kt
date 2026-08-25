package io.mszymanski.orknux.server.library

import io.mszymanski.orknux.connector.proxy.ProxyRouter
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.zip.GZIPInputStream

@ConfigurationProperties(prefix = "orknux.library.registry")
data class LibraryRegistryProperties(
    /**
     * Where a package is fetched from. Empty switches fetching off altogether.
     *
     * Empty is the offline installation's setting, and it is a real one rather
     * than a way of saying "leave the default": nothing here is fetched on a
     * timer or on anybody's behalf, so a registry that cannot be reached costs
     * only the administrator who pressed Install — but a field offering to
     * install from a registry that is not there is a field that lies, so an
     * installation without one says so and the screen shows the upload alone.
     */
    val url: String = "https://registry.npmjs.org",
    /** How long the registry has to answer, for the metadata and for the file. */
    val timeout: Duration = Duration.ofSeconds(30),
)

/**
 * Fetching one package from an npm registry, once, at an administrator's request.
 *
 * **Why this exists at all, given what [ScriptLibrary] says.** That note rules out
 * a library that *is* a name from a registry, and it still does. What is fetched
 * here is a file, and the file is what is stored and what runs; the registry is a
 * way of *getting* it and is never consulted again. Nothing about the sandbox
 * changes — it has no network before this and none after — and an installation
 * with no registry configured goes on being an installation where a library is a
 * file somebody uploads.
 *
 * The three things that have to be true for that to be honest, and where each one
 * is enforced:
 *
 * **A version is pinned, by the person asking.** [resolve] refuses `latest`, a
 * range and a bare name. There is no default, because a default would be a
 * version this server picked on a day nobody was looking, and "what code is
 * running here" would then have as many answers as there were installs.
 *
 * **What was fetched is recorded and checked.** The registry says what the file it
 * is about to serve hashes to; [verify] hashes what arrived and compares. The
 * resolved version, the URL, that hash and the path inside the package are all
 * stored on the row, so the artefact can be shown to be what it claims — and can
 * be fetched again elsewhere and compared.
 *
 * **It goes out the way everything else does.** The client is built from
 * [ProxyRouter.builder], so an installation whose outbound traffic has to cross a
 * proxy reaches the registry through it. A second HTTP client here would be the
 * bug #176 found in Slack in a new place: rules that cover most of the product and
 * silently miss one screen.
 *
 * **On dependencies, this refuses rather than bundles.** See [Fetched] and
 * [LibraryDependsException].
 */
@Component
@EnableConfigurationProperties(LibraryRegistryProperties::class)
class NpmRegistry(
    private val properties: LibraryRegistryProperties,
    private val mapper: ObjectMapper,
    proxies: ProxyRouter,
) {

    private val client: HttpClient = proxies.builder()
        .connectTimeout(properties.timeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** Where packages come from, or null when this installation fetches none. */
    val registry: String? get() = properties.url.trim().trimEnd('/').ifEmpty { null }

    /**
     * Fetches one package and answers the single module inside it, or refuses.
     *
     * [spec] is what an administrator typed: `random@4.1.0`, or
     * `@scope/name@1.2.3`. Everything it can be wrong about is answered as a
     * sentence, because this is a field somebody is looking at.
     *
     * **What is trusted here, and what is not.** The registry is trusted, because
     * an operator configured it — [spec] is a package name and never an address,
     * so nobody with this screen can aim the server at a host of their choosing.
     * The registry's own `dist.tarball` is followed, and redirects with it, for
     * the same reason: a mirror serves its files from wherever it serves them.
     * What is *not* trusted is what comes back, and that is what [verify] is
     * for — the bytes have to hash to the value the metadata named before
     * anything is read out of them.
     */
    fun fetch(spec: String): Fetched {
        val registry = registry ?: throw LibraryRegistryOffException()
        val (name, version) = resolve(spec)

        val manifest = json("$registry/${encoded(name)}/$version", "$name@$version")
        val distribution = manifest.path("dist")
        val tarball = distribution.path("tarball").asString("").trim()
        if (!tarball.startsWith("http://") && !tarball.startsWith("https://")) {
            throw LibraryRegistrySilentException("$name@$version", "it named no file to download")
        }

        // The version the registry answered with, not the one that was asked for.
        // They agree for an exact version, and the registry is the one that knows.
        val resolved = manifest.path("version").asString(version).ifBlank { version }

        val archive = bytes(tarball, "$name@$resolved")
        val integrity = verify(archive, distribution, "$name@$resolved")

        val described = mapper.readTree(
            entry(archive, PACKAGE_JSON) ?: throw LibraryRegistrySilentException(
                "$name@$resolved",
                "its file holds no package.json",
            ),
        )

        val (path, source) = entryOf(archive, described) ?: throw LibraryNoEntryException("$name@$resolved")

        val format = LibrarySource.formatOf(source)
        LibrarySource.imported(source)?.let {
            throw LibraryDependsException("$name@$resolved", path, "imports", it)
        }
        if (format == LibrarySource.COMMONJS) {
            LibrarySource.required(source)?.let {
                throw LibraryDependsException("$name@$resolved", path, "requires", it)
            }
        }

        return Fetched(
            packageName = name,
            version = resolved,
            url = tarball,
            integrity = integrity,
            entry = path,
            source = source,
        )
    }

    /**
     * A package name and an exact version, or a sentence about why that is not
     * what was typed.
     *
     * The version has no default on purpose. `latest` and a range are both
     * refused by the same rule and for the same reason: a library is the answer
     * to "what code is running in this installation", and a specification that
     * resolves differently tomorrow is not an answer.
     */
    internal fun resolve(spec: String): Pair<String, String> {
        val typed = spec.trim()
        if (typed.isEmpty()) throw LibraryPackageInvalidException(typed, "name a package: random@4.1.0")

        // The `@` that separates the version is the last one, since a scoped
        // package's name starts with one of its own.
        val at = typed.lastIndexOf('@')
        val name = if (at <= 0) typed else typed.take(at)
        val version = if (at <= 0) "" else typed.substring(at + 1).trim()

        if (!PACKAGE.matches(name)) {
            throw LibraryPackageInvalidException(typed, "\"$name\" is not an npm package name")
        }
        if (!VERSION.matches(version)) {
            throw LibraryPackageInvalidException(
                typed,
                "name an exact version — $name@1.2.3 — because a range or \"latest\" is not an answer to " +
                    "what code is running here",
            )
        }
        return name to version
    }

    /**
     * The file this installs, with the path it came from, or null.
     *
     * **An ES module is preferred wherever the package ships one**, and that is
     * decided by reading the candidate rather than by trusting the field it was
     * named under: `exports.default` is as often the CommonJS build as not, and a
     * package that publishes both should have its module installed even where its
     * manifest lists them the other way round. So the candidates are walked in
     * [modules]' order, the first that is genuinely an ES module wins, and the
     * first that exists at all is the fallback.
     *
     * More than one candidate is walked rather than the first taken for a second
     * reason too: a package's `exports` frequently points at a file it did not
     * publish — a `./src` entry a build step was supposed to produce — and
     * falling through is what npm's own resolver would do next.
     */
    private fun entryOf(archive: ByteArray, described: JsonNode): Pair<String, String>? {
        var fallback: Pair<String, String>? = null
        for (candidate in modules(described)) {
            val source = entry(archive, "package/$candidate") ?: continue
            if (LibrarySource.formatOf(source) == LibrarySource.ESM) return candidate to source
            if (fallback == null) fallback = candidate to source
        }
        return fallback
    }

    /**
     * The files in the package that could be the entry, best first.
     *
     * ES builds first and the CommonJS ones after them, because the sandbox runs
     * a module natively and a CommonJS file only through the wrapper
     * [LibrarySource.runnable] puts round it. Within the ES half `exports` is
     * asked before `module`, and `main` counts as one only where `"type":
     * "module"` says it is.
     *
     * Then the same manifest is read again for what it says is CommonJS: the
     * `require` condition of `exports`, and `main`. Until #274 those were not
     * candidates at all and a package shipping only them was refused; a CommonJS
     * file that requires nothing is a self-contained module with a different
     * spelling, and refusing it was a rule wider than its reason.
     */
    internal fun modules(described: JsonNode): List<String> {
        val found = LinkedHashSet<String>()

        val exports = described.path("exports")
        if (exports.isTextual) file(exports.asString(""))?.let(found::add)
        val root = if (exports.isObject) (if (exports.has(".")) exports.path(".") else exports) else null
        if (root != null) conditions(root, found, 0, PREFERRED)

        file(described.path("module").asString(""))?.let(found::add)
        val main = file(described.path("main").asString(""))
        if (described.path("type").asString("") == "module") main?.let(found::add)

        if (root != null) conditions(root, found, 0, REQUIRED)
        main?.let(found::add)
        return found.toList()
    }

    /**
     * Walks an `exports` condition tree, following the conditions it is given.
     *
     * Called twice: once with [PREFERRED], the conditions that name an ES module,
     * and once with [REQUIRED], which is the CommonJS branch. A subpath is never
     * asked for — a library here is one module, and a package's second entry
     * point is a second file it would have to import.
     */
    private fun conditions(node: JsonNode, into: MutableSet<String>, depth: Int, asked: List<String>) {
        if (depth > MAX_CONDITIONS) return
        if (node.isTextual) {
            file(node.asString(""))?.let(into::add)
            return
        }
        if (!node.isObject) return
        for (condition in asked) {
            if (node.has(condition)) conditions(node.path(condition), into, depth + 1, asked)
        }
    }

    /** A path inside the package, or null for anything that is not a plain file. */
    private fun file(named: String): String? {
        val path = named.trim().removePrefix("./")
        if (path.isEmpty() || path.startsWith("/") || path.contains("..") || path.contains('*')) return null
        return path
    }

    /**
     * Hashes what arrived and compares it with what the registry said it would be.
     *
     * `dist.integrity` is the modern spelling and `dist.shasum` the old one; a
     * registry answering with neither is refused rather than trusted, because an
     * artefact nothing can be compared against is exactly the thing this feature
     * is not allowed to store. What comes back is the claim that was checked, and
     * it goes on the row.
     */
    internal fun verify(archive: ByteArray, distribution: JsonNode, named: String): String {
        val integrity = distribution.path("integrity").asString("").trim()
        if (integrity.isNotEmpty()) {
            val algorithm = integrity.substringBefore('-', "")
            val expected = integrity.substringAfter('-', "")
            val digest = when (algorithm) {
                "sha512" -> "SHA-512"
                "sha384" -> "SHA-384"
                "sha256" -> "SHA-256"
                else -> throw LibraryRegistrySilentException(named, "it hashed the file with \"$algorithm\"")
            }
            val actual = Base64.getEncoder().encodeToString(MessageDigest.getInstance(digest).digest(archive))
            if (actual != expected) throw LibraryIntegrityException(named, integrity, "$algorithm-$actual")
            return integrity
        }

        val shasum = distribution.path("shasum").asString("").trim().lowercase()
        if (shasum.isEmpty()) throw LibraryRegistrySilentException(named, "it did not say what the file hashes to")
        val actual = MessageDigest.getInstance("SHA-1").digest(archive).joinToString("") { "%02x".format(it) }
        if (actual != shasum) throw LibraryIntegrityException(named, "sha1-$shasum", "sha1-$actual")
        return "sha1-$shasum"
    }

    private fun json(url: String, named: String): JsonNode {
        val answered = get(url, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), named)
        if (answered.statusCode() == 404) throw LibraryPackageMissingException(named)
        if (answered.statusCode() !in 200..299) {
            throw LibraryRegistrySilentException(named, "the registry answered ${answered.statusCode()}")
        }
        return try {
            mapper.readTree(answered.body())
        } catch (failure: Exception) {
            throw LibraryRegistrySilentException(named, failure.message ?: "the registry answered nothing readable")
        }
    }

    private fun bytes(url: String, named: String): ByteArray {
        val answered = get(url, HttpResponse.BodyHandlers.ofByteArray(), named)
        if (answered.statusCode() !in 200..299) {
            throw LibraryRegistrySilentException(named, "its file answered ${answered.statusCode()}")
        }
        val body = answered.body()
        if (body.size > MAX_ARCHIVE) {
            throw LibraryRegistrySilentException(named, "its file is larger than ${MAX_ARCHIVE / 1024 / 1024} MB")
        }
        return body
    }

    private fun <T> get(url: String, handler: HttpResponse.BodyHandler<T>, named: String): HttpResponse<T> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(properties.timeout)
            .header("Accept", "application/json")
            .header("User-Agent", "orknux")
            .GET()
            .build()
        return try {
            client.send(request, handler)
        } catch (failure: IOException) {
            throw LibraryRegistryUnreachableException(named, failure.message ?: "it could not be reached")
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
            throw LibraryRegistryUnreachableException(named, "the fetch was interrupted")
        }
    }

    /**
     * One file out of a gzipped tar, as text, or null when it is not in there.
     *
     * A reader rather than a library, because this reads two files out of one
     * archive and needs no more of tar than that. It knows regular files, the
     * PAX and GNU records that carry a name too long for a header, and how to
     * skip everything else; what it will not do is read past [MAX_ENTRY] or past
     * the archive, so a file that claims to be enormous costs a refusal rather
     * than the heap.
     */
    internal fun entry(archive: ByteArray, path: String): String? {
        GZIPInputStream(ByteArrayInputStream(archive)).use { stream ->
            var override: String? = null
            while (true) {
                val header = block(stream) ?: return null
                if (header.all { it == 0.toByte() }) return null

                val size = octal(header, 124, 12)
                val padded = ((size + BLOCK - 1) / BLOCK) * BLOCK
                val kind = header[156].toInt().toChar()
                val name = override ?: string(header, 0, 100)
                override = null

                when (kind) {
                    // A PAX or GNU record whose payload is the next file's name.
                    'x', 'X', 'L' -> {
                        val held = String(read(stream, size.toInt()), StandardCharsets.UTF_8)
                        override = if (kind == 'L') held.trimEnd('\u0000') else pax(held)
                        skip(stream, padded - size)
                    }

                    // A regular file: "0" in ustar, and NUL in the older
                    // spelling, which is still what some writers produce.
                    '0', '\u0000' -> {
                        if (name == path) {
                            if (size > MAX_ENTRY) return null
                            return String(read(stream, size.toInt()), StandardCharsets.UTF_8)
                        }
                        skip(stream, padded)
                    }

                    else -> skip(stream, padded)
                }
            }
        }
    }

    /** The `path=` record out of a PAX extended header. */
    private fun pax(held: String): String? = held.lineSequence()
        .mapNotNull { line -> line.substringAfter("path=", "").ifEmpty { null } }
        .firstOrNull()

    private fun block(stream: InputStream): ByteArray? = try {
        read(stream, BLOCK)
    } catch (_: EOFException) {
        null
    }

    private fun read(stream: InputStream, count: Int): ByteArray {
        val held = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val took = stream.read(held, filled, count - filled)
            if (took < 0) throw EOFException()
            filled += took
        }
        return held
    }

    private fun skip(stream: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val stepped = stream.skip(left)
            if (stepped <= 0) {
                if (stream.read() < 0) return
                left--
            } else {
                left -= stepped
            }
        }
    }

    private fun string(header: ByteArray, at: Int, length: Int): String {
        val end = (at until at + length).firstOrNull { header[it] == 0.toByte() } ?: (at + length)
        return String(header, at, end - at, StandardCharsets.UTF_8)
    }

    private fun octal(header: ByteArray, at: Int, length: Int): Long =
        string(header, at, length).trim().ifEmpty { "0" }.toLongOrNull(8) ?: 0

    /** A scoped name's slash is escaped; the registry takes either spelling. */
    private fun encoded(name: String): String = name.replace("/", "%2f")

    private companion object {
        const val PACKAGE_JSON = "package/package.json"

        const val BLOCK = 512

        /** A published package, compressed. Larger than any library may be. */
        const val MAX_ARCHIVE = 32L * 1024 * 1024

        /** One file out of it. The upload's own limit refuses it again after this. */
        const val MAX_ENTRY = 8L * 1024 * 1024

        /** How deep an `exports` tree is followed before it is somebody's mistake. */
        const val MAX_CONDITIONS = 8

        /**
         * The `exports` conditions that name an ES module, best first.
         *
         * `require` is missing here and has [REQUIRED] to itself, because the two
         * are asked in two passes: everything a package calls a module is a
         * candidate before anything it calls CommonJS. `node` and `browser` are
         * here because a package that ships both usually names the module under
         * one of them rather than at the top.
         */
        val PREFERRED = listOf("module", "import", "browser", "node", "default")

        /**
         * The CommonJS branch, asked only once the ES ones have been.
         *
         * `node` and `default` are repeated because `require` is often nested
         * under one of them, and a condition already visited costs a set
         * membership.
         */
        val REQUIRED = listOf("require", "node", "default")

        /** npm's own rule for a name, scope included, and its length limit. */
        val PACKAGE = Regex("(?:@[a-z0-9][a-z0-9._-]{0,100}/)?[a-z0-9][a-z0-9._-]{0,100}")

        /** Exactly one version. No range, no tag, no `latest`. */
        val VERSION = Regex("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")
    }
}

/**
 * One module, fetched from a registry, with everything the row records about it.
 *
 * [integrity] is the registry's own claim about the archive, verified against
 * what arrived. [entry] is the path inside the package, because "which of the
 * eleven files in this tarball is the thing running" is otherwise unanswerable.
 */
data class Fetched(
    val packageName: String,
    val version: String,
    val url: String,
    val integrity: String,
    val entry: String,
    val source: String,
)
