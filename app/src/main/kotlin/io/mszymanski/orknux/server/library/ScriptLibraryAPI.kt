package io.mszymanski.orknux.server.library

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import io.mszymanski.orknux.server.dependency.ComponentDependants
import io.mszymanski.orknux.server.dependency.DependantView
import io.mszymanski.orknux.server.dependency.DependencyAPI
import io.mszymanski.orknux.server.dependency.DependencyKind
import io.mszymanski.orknux.server.dependency.phrases
import io.mszymanski.orknux.server.security.WorkspaceAccess
import io.mszymanski.orknux.workflow.script.LibraryInspection
import io.mszymanski.orknux.workflow.script.ScriptRunner
import org.springframework.data.repository.findByIdOrNull
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import tools.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime

/**
 * Loading a library into the installation, and taking it out again.
 *
 * Administrators only. A library is installation-wide, and the reason it is —
 * rather than a thing each workspace uploads for itself — is on [ScriptLibrary].
 *
 * The upload is REST for the same reason a plugin's is: what crosses is a file,
 * and a multipart form is what a browser makes of a file picker. Everything else
 * is GraphQL.
 *
 * The file is evaluated in the sandbox it will run in before anything is stored,
 * and refused if it cannot be. That is not a security check — a library gets no
 * more than a function gets, which is nothing — it is so that "this file is not a
 * module with a default export" is answered while somebody is looking at it.
 */
@RestController
class ScriptLibraryUploadAPI(
    private val libraries: ScriptLibraryRepository,
    private val access: WorkspaceAccess,
    private val store: LibraryStore,
) {

    @PostMapping("/api/libraries")
    @Transactional
    fun upload(
        @RequestParam("file") file: MultipartFile,
        /**
         * What it was written in, when that was TypeScript.
         *
         * Sent by the interface, which compiles before uploading: the sandbox runs
         * JavaScript and this server has no compiler. Kept only so the library can
         * be downloaded as what somebody wrote, and never evaluated.
         */
        @RequestParam("typescript", required = false) typescript: String?,
    ): ResponseEntity<Any> {
        access.requireAdmin()

        if (file.isEmpty) throw LibraryEmptyException()

        val filename = file.originalFilename?.trim()?.ifEmpty { null } ?: "library.js"
        if (!filename.endsWith(".js") && !filename.endsWith(".mjs")) throw LibraryNotJavaScriptException(filename)

        val source = text(file.bytes)
        val key = filename.removeSuffix(".mjs").removeSuffix(".js")

        val replaced = libraries.findByKey(key) != null
        val saved = store.store(
            key = key,
            filename = filename,
            source = source,
            typescript = typescript?.ifBlank { null },
            sizeBytes = file.size,
        )
        return ResponseEntity.ok(mapOf("id" to requireNotNull(saved.id), "key" to saved.key, "replaced" to replaced))
    }

    /** The library as it was written, for downloading. TypeScript where there is any. */
    @GetMapping("/api/libraries/{id}/source")
    fun download(@PathVariable id: Long): ResponseEntity<String> {
        access.requireAdmin()
        val library = libraries.findByIdOrNull(id) ?: throw LibraryNotFoundException(id)

        val written = library.typescript
        val extension = if (written == null) "js" else "ts"
        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("text/plain"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${library.key}.$extension\"")
            .body(written ?: library.source)
    }

    @ExceptionHandler(
        LibraryEmptyException::class,
        LibraryTooLargeException::class,
        LibraryNotJavaScriptException::class,
        LibraryNotTextException::class,
        LibraryKeyInvalidException::class,
        LibraryUnreadableException::class,
    )
    fun refused(failure: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("message" to (failure.message ?: "That file could not be loaded")))

    /** The bytes as the text they are supposed to be, strictly, or a refusal. */
    private fun text(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (failure: CharacterCodingException) {
        throw LibraryNotTextException()
    }

}

/**
 * The one place a library becomes a row.
 *
 * Two doors reach it — a file somebody uploaded and a package somebody named —
 * and they must not disagree about what a stored library is. What this decides is
 * everything that is true of a library however it arrived: it is evaluated in the
 * sandbox it will run in before anything is stored, what its export turned out to
 * hold is read off the value, and a key that is already loaded is replaced in
 * place rather than duplicated, so nothing importing it is repointed.
 *
 * The provenance is the only thing that differs, and it is passed in rather than
 * worked out here: [Fetched] where a registry served the file, null where somebody
 * chose it, and null is what makes the row say `UPLOAD`.
 *
 * The size limit and the shape of a key live here for the same reason. They are
 * properties of a library rather than of an upload, and a copy in each door is two
 * copies to keep level — the door that fetches a package would otherwise be free
 * to store one twice the size of anything anybody could upload.
 */
@Component
class LibraryStore(
    private val libraries: ScriptLibraryRepository,
    private val scripts: ScriptRunner,
    private val mapper: ObjectMapper,
) {

    @Transactional
    fun store(
        key: String,
        filename: String,
        source: String,
        typescript: String? = null,
        sizeBytes: Long,
        fetched: Fetched? = null,
    ): ScriptLibrary {
        if (!KEY.matches(key)) throw LibraryKeyInvalidException(key)
        if (sizeBytes > MAX_SIZE) throw LibraryTooLargeException(MAX_SIZE / 1024)

        // Which of the two spellings this is, and whether it is on its own. Asked
        // here rather than in the door that fetched it, so that a CommonJS file
        // somebody uploads is held to exactly the rule a package is: a `require`
        // in a file that is going to be *run* as CommonJS is a call into a second
        // package, and one stored without this would install and fail at its
        // first use. An ES module is never asked, because a bundle mentions
        // `require` inside a shim it never reaches - see LibrarySource.required.
        val format = LibrarySource.formatOf(source)
        if (format == LibrarySource.COMMONJS) {
            LibrarySource.required(source)?.let {
                throw LibraryUnreadableException(
                    "it calls require(\"$it\"), and a library has to be one self-contained file. " +
                        "This installation does not bundle.",
                )
            }
        }

        val read = when (val answered = scripts.library(LibrarySource.runnable(source, format))) {
            is LibraryInspection.Read -> answered
            is LibraryInspection.Unreadable -> throw LibraryUnreadableException(answered.reason)
        }

        val members = mapper.writeValueAsString(read.members.map { mapOf("name" to it.name, "callable" to it.callable) })
        val library = libraries.findByKey(key) ?: ScriptLibrary(
            key = key,
            name = key,
            filename = filename.takeLast(MAX_NAME),
            source = source,
            sizeBytes = sizeBytes,
            sha256 = digest(source),
        )

        return libraries.save(
            library.apply {
                this.name = key
                this.filename = filename.takeLast(MAX_NAME)
                this.source = source
                this.typescript = typescript
                this.sizeBytes = sizeBytes
                this.sha256 = digest(source)
                this.declaredMembers = members
                this.callable = read.callable
                this.sourceFormat = format
                this.uploadedAt = OffsetDateTime.now()
                this.uploadedBy = currentUser()
                // Rewritten in full both ways round. A registry install over an
                // upload has a provenance to record, and an upload over a
                // registry install has none - and a row still claiming a package
                // whose file was replaced by hand is worse than one claiming
                // nothing, because it would be believed.
                this.origin = if (fetched == null) ScriptLibrary.ORIGIN_UPLOAD else ScriptLibrary.ORIGIN_REGISTRY
                this.originPackage = fetched?.packageName
                this.originVersion = fetched?.version
                this.originUrl = fetched?.url
                this.originIntegrity = fetched?.integrity
                this.originEntry = fetched?.entry
            },
        )
    }

    private fun digest(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        const val MAX_NAME = 200

        /** A bundle, and bundles are large. Small enough that the row stays a row. */
        const val MAX_SIZE = 4L * 1024 * 1024

        /**
         * What a library may be called: what a package is usually called.
         *
         * Dots and dashes allowed, unlike a plugin's key, because this is never
         * written into anybody's code — a workspace importing a library gives it a
         * local name of its own. `date-fns` should be loadable as `date-fns`.
         */
        val KEY = Regex("[A-Za-z][A-Za-z0-9._-]{0,63}")
    }
}

/** Listing what is loaded, seeing what depends on it, and taking one out. */
@Controller
class ScriptLibraryAPI(
    private val libraries: ScriptLibraryRepository,
    private val dependants: ComponentDependants,
    private val dependencies: DependencyAPI,
    private val access: WorkspaceAccess,
    private val mapper: ObjectMapper,
    private val registry: NpmRegistry,
    private val store: LibraryStore,
) {

    /**
     * Whether a package can be named here, and where one would come from.
     *
     * Asked by the screen so that an installation with no registry shows the
     * upload alone. A field offering to fetch from a registry that is not
     * configured is a field that fails on being used, which is the one thing an
     * offline installation should never be shown.
     */
    @QueryMapping
    fun libraryRegistry(): LibraryRegistryStatus {
        access.requireAdmin()
        val url = registry.registry
        return LibraryRegistryStatus(configured = url != null, url = url.orEmpty())
    }

    /**
     * Fetches one package and loads what is inside it as a library.
     *
     * **Once, here, into the database.** The file is what is stored and what
     * runs, exactly as an uploaded one is; the registry is consulted at this
     * moment and never again, and nothing in the sandbox gains a network. See
     * [NpmRegistry] for the three rules that make that honest — a pinned
     * version, a verified hash, and the proxy rules — and [LibraryDependsException]
     * for what happens to a package that is not one file.
     *
     * `spec` is `random@4.1.0`. The key is the package's name with the scope
     * folded into it, so `@scope/thing` loads as `scope-thing`, and loading a key
     * that is already there replaces it in place the way a re-upload does.
     */
    @MutationMapping
    @Transactional
    fun installScriptLibrary(@Argument spec: String): ScriptLibraryView {
        access.requireAdmin()

        val fetched = registry.fetch(spec)
        val stored = store.store(
            // The key and the size are the store's rules, not this one's: a
            // library is a library however it arrived here.
            key = keyOf(fetched.packageName),
            filename = "${fetched.packageName}@${fetched.version}/${fetched.entry}",
            source = fetched.source,
            sizeBytes = fetched.source.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            fetched = fetched,
        )
        return describe(stored, usersOf(requireNotNull(stored.id)))
    }

    /** Everything loaded, with what imports it. Administrators. */
    @QueryMapping
    fun scriptLibraries(): List<ScriptLibraryView> {
        access.requireAdmin()
        return libraries.findAllByOrderByNameAsc().map { describe(it, usersOf(requireNotNull(it.id))) }
    }

    /**
     * The libraries a workspace may import, for the editor's picker.
     *
     * The same rows, without what depends on them: who else imports a library is an
     * administrator's question, and the people writing functions are not
     * administrators. A picker has to be able to offer `date-fns` without being
     * allowed to see every workspace that uses it — which is the same line the
     * function picker already draws around plugins.
     */
    @QueryMapping
    fun workspaceLibraries(@Argument workspaceId: Long): List<ScriptLibraryView> {
        access.requireVisible(workspaceId)
        return libraries.findAllByOrderByNameAsc().map { describe(it, usedBy = emptyList()) }
    }

    /**
     * Takes a library out of the installation.
     *
     * Refused while anything imports it, and the refusal names them. A library
     * removed out from under a function is a `TypeError` in the middle of a run,
     * which is the worst moment and the worst wording to learn this in.
     */
    @MutationMapping
    @Transactional
    fun deleteScriptLibrary(@Argument id: Long): Boolean {
        access.requireAdmin()
        val library = libraries.findByIdOrNull(id) ?: throw LibraryNotFoundException(id)

        val used = dependants.of(DependencyKind.LIBRARY, id)
        if (used.isNotEmpty()) throw LibraryInUseException(used.phrases())

        libraries.delete(library)
        return true
    }

    private fun describe(library: ScriptLibrary, usedBy: List<DependantView>) = ScriptLibraryView(
        id = requireNotNull(library.id),
        key = library.key,
        name = library.name,
        filename = library.filename,
        // GraphQL has no long, and the rest of this schema reports sizes as floats.
        sizeBytes = library.sizeBytes.toDouble(),
        sha256 = library.sha256,
        callable = library.callable,
        format = library.sourceFormat,
        members = mapper.readTree(library.declaredMembers).values().map { held ->
            LibraryMemberView(
                name = held.path("name").asString(""),
                callable = held.path("callable").asBoolean(false),
            )
        },
        usedBy = usedBy,
        uploadedAt = library.uploadedAt.toString(),
        uploadedBy = library.uploadedBy,
        registry = registryOf(library),
    )

    /**
     * Where it came from, or null for a file somebody chose.
     *
     * Built only where every part of it is there. A half-filled provenance is
     * one nobody can check, and a row that says which package without saying
     * what it hashed to would still be shown as though it had been vouched for.
     */
    private fun registryOf(library: ScriptLibrary): LibraryRegistryView? {
        if (library.origin != ScriptLibrary.ORIGIN_REGISTRY) return null
        return LibraryRegistryView(
            packageName = library.originPackage ?: return null,
            version = library.originVersion ?: return null,
            url = library.originUrl.orEmpty(),
            integrity = library.originIntegrity.orEmpty(),
            entry = library.originEntry.orEmpty(),
        )
    }

    /**
     * A package's name as a library key.
     *
     * `random` is `random`, and a scoped package folds its scope in with a dash:
     * `@scope/thing` is `scope-thing`. A key holds no `@` and no slash, since it
     * is what an administrator matches a re-load against and one nobody can say
     * out loud is no use for that — and it is not what anybody's code types,
     * which is a local name of the importer's own.
     */
    private fun keyOf(packageName: String): String =
        packageName.removePrefix("@").replace('/', '-').takeLast(64)

    /**
     * Every function and tool that imports this library, with the workspace it is in.
     *
     * Through [DependencyAPI.visible] rather than raw, so that the one rule about
     * naming something in a workspace the reader cannot open lives in one place.
     * This listing is an administrator's and an administrator sees every
     * workspace, so nothing is ever dropped here — but the rule is not this
     * screen's to restate.
     */
    private fun usersOf(id: Long): List<DependantView> =
        dependencies.visible(dependants.of(DependencyKind.LIBRARY, id)).entries
}

@Component
class ScriptLibraryExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(exception: Throwable, environment: DataFetchingEnvironment): GraphQLError? {
        val errorType = when (exception) {
            is LibraryInUseException,
            is LibraryUnreadableException,
            is LibraryKeyInvalidException,
            is LibraryTooLargeException,
            // Every way an install can be refused. Each of them carries a sentence
            // meant for whoever typed the package name, so all of them are
            // BAD_REQUEST and none is turned into a shorter one on the way out -
            // "it could not be fetched" would leave somebody guessing between a
            // typo, a proxy and a package that ships no module.
            is LibraryRegistryOffException,
            is LibraryPackageInvalidException,
            is LibraryRegistryUnreachableException,
            is LibraryRegistrySilentException,
            is LibraryIntegrityException,
            is LibraryNoEntryException,
            is LibraryDependsException,
            -> ErrorType.BAD_REQUEST

            is LibraryNotFoundException,
            is LibraryPackageMissingException,
            -> ErrorType.NOT_FOUND

            else -> return null
        }

        return GraphQLError.newError()
            .errorType(errorType)
            .message(exception.message)
            .path(environment.executionStepInfo.path)
            .location(environment.field.sourceLocation)
            .build()
    }
}
