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
    private val scripts: ScriptRunner,
    private val mapper: ObjectMapper,
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
        if (file.size > MAX_SIZE) throw LibraryTooLargeException(MAX_SIZE / 1024)

        val filename = file.originalFilename?.trim()?.ifEmpty { null } ?: "library.js"
        if (!filename.endsWith(".js") && !filename.endsWith(".mjs")) throw LibraryNotJavaScriptException(filename)

        val source = text(file.bytes)
        val key = filename.removeSuffix(".mjs").removeSuffix(".js")
        if (!KEY.matches(key)) throw LibraryKeyInvalidException(key)

        val read = when (val answered = scripts.library(source)) {
            is LibraryInspection.Read -> answered
            is LibraryInspection.Unreadable -> throw LibraryUnreadableException(answered.reason)
        }

        val members = mapper.writeValueAsString(read.members.map { mapOf("name" to it.name, "callable" to it.callable) })
        val existing = libraries.findByKey(key)
        val library = existing?.apply {
            this.name = key
            this.filename = filename.takeLast(MAX_NAME)
            this.source = source
            this.typescript = typescript?.ifBlank { null }
            this.sizeBytes = file.size
            this.sha256 = digest(source)
            this.declaredMembers = members
            this.callable = read.callable
            this.uploadedAt = OffsetDateTime.now()
            this.uploadedBy = currentUser()
        } ?: ScriptLibrary(
            key = key,
            name = key,
            filename = filename.takeLast(MAX_NAME),
            source = source,
            typescript = typescript?.ifBlank { null },
            sizeBytes = file.size,
            sha256 = digest(source),
            declaredMembers = members,
            callable = read.callable,
            uploadedBy = currentUser(),
        )

        val saved = libraries.save(library)
        return ResponseEntity.ok(mapOf("id" to requireNotNull(saved.id), "key" to saved.key, "replaced" to (existing != null)))
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

    private fun digest(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun currentUser(): String = SecurityContextHolder.getContext().authentication?.name ?: "system"

    private companion object {
        /** A bundle, and bundles are large. Small enough that the row stays a row. */
        const val MAX_SIZE = 4L * 1024 * 1024

        const val MAX_NAME = 200

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
) {

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
        members = mapper.readTree(library.declaredMembers).values().map { held ->
            LibraryMemberView(
                name = held.path("name").asString(""),
                callable = held.path("callable").asBoolean(false),
            )
        },
        usedBy = usedBy,
        uploadedAt = library.uploadedAt.toString(),
        uploadedBy = library.uploadedBy,
    )

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
            -> ErrorType.BAD_REQUEST

            is LibraryNotFoundException -> ErrorType.NOT_FOUND

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
