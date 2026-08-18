package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.server.workspace.WorkspaceNotFoundException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * What the REST endpoints answer when access says no.
 *
 * The GraphQL side has resolvers for these; the REST side had nothing, so every
 * one of them arrived as a 500 with a stack trace in the log and
 * "Internal Server Error" on the wire. Asking about a workspace that does not
 * exist is not the server breaking — it is the answer.
 *
 * It read as a 401 until recently, which was worse: the error page is a second
 * dispatch, and the security chain was refusing it. That is fixed in
 * [SecurityConfig]; this is the other half, and without it the honest status is
 * an honest 500.
 *
 * Attachments, transcription, speech, the quick chat and the MCP endpoint all
 * throw these. One place to answer them, rather than a try/catch in each.
 */
@RestControllerAdvice
class RestAccessExceptionHandler {

    /**
     * Absent and forbidden are the same answer.
     *
     * A workspace somebody may not see is reported exactly as one that is not
     * there, the way it is everywhere else here: "you may not see this" confirms
     * that this is somebody's, which is a fact worth not handing out.
     */
    @ExceptionHandler(WorkspaceNotFoundException::class, WorkspaceForbiddenException::class)
    fun notFound(failure: RuntimeException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(mapOf("error" to (failure.message ?: "That is not here")))

    @ExceptionHandler(AdminRequiredException::class)
    fun forbidden(failure: AdminRequiredException): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(mapOf("error" to (failure.message ?: "That requires the administrator role")))
}
