package io.mszymanski.orknux.server.task

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * The same judgement [TaskExceptionResolver] makes for GraphQL, made for the one
 * task endpoint that is not.
 *
 * Written for the reason its counterpart next door in the chat was: a plain MVC
 * call never reaches the GraphQL resolver, so a task somebody no longer has
 * would come back as a 500 with Spring's own body - which says "Internal Server
 * Error" and reads as a fault here rather than as a link that has gone stale.
 *
 * Safe to answer with a status because [TaskStreamAPI.stream] checks before the
 * stream opens. Once the first byte is written the status has already gone, and
 * a stream in trouble after that says so in a frame instead.
 */
@RestControllerAdvice(assignableTypes = [TaskStreamAPI::class])
class TaskStreamExceptionHandler {

    @ExceptionHandler(TaskNotFoundException::class)
    fun notFound(exception: TaskNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message ?: "No such task")
}
