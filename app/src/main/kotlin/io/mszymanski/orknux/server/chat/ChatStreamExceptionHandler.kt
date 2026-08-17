package io.mszymanski.orknux.server.chat

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * The same judgement [ChatExceptionResolver] makes for GraphQL, made for the one
 * chat endpoint that is not GraphQL.
 *
 * Streaming a reply is a plain MVC call, so the resolver never sees it: a chat
 * with no model chosen came back as a 500 with Spring's default body, which
 * says only "Internal Server Error" and reads as a fault in the server rather
 * than something the caller can put right. These are all the caller's to act on
 * — pick a model, write some text — so they are bad requests, and the reason
 * travels in the detail where the browser can show it.
 *
 * This is safe to answer with a status because [ChatStreamAPI.stream] does its
 * checks before the stream opens; once the first byte is written the status has
 * already gone.
 */
@RestControllerAdvice(assignableTypes = [ChatStreamAPI::class])
class ChatStreamExceptionHandler {

    @ExceptionHandler(ChatSessionNotFoundException::class)
    fun notFound(exception: ChatSessionNotFoundException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.message ?: "No such chat")

    @ExceptionHandler(
        ChatModelNotChosenException::class,
        ChatModelUnusableException::class,
        ChatAgentUnusableException::class,
        ChatMessageEmptyException::class,
        ChatTitleInvalidException::class,
        ChatDisabledException::class,
    )
    fun badRequest(exception: RuntimeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.message ?: "The chat cannot answer that")
}
