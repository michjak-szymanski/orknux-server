package io.mszymanski.orknux.server.graphql

import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.ErrorType

/**
 * A refusal an exception resolver hands back, said twice: in English, and as
 * something a client can say in its own language.
 *
 * ---------------------------------------------------------------------------
 * Why the translating happens in the browser and not here
 *
 * Every one of these sentences is produced where the code is, and most of them
 * carry a value - a name that was taken, a file that was too big, the four
 * things a field would accept. The obvious way to translate them is a
 * `MessageSource` at the throw site, and it is the wrong one here, for three
 * reasons that all point the same way.
 *
 * The audience is not one audience. The same exception reaches a person looking
 * at a screen, an agent calling an `orknux_*` tool, and an operator reading a
 * log. Only the first of those has a language; the other two must stay English
 * for ever, and a server that has already translated has thrown away the
 * English it needed for them.
 *
 * The server does not reliably know the language. The person's choice is on
 * their row, which means a lookup on every throw, on a thread that may be a
 * scheduler's or a workflow runner's and have no person on it at all. The
 * browser, which is the only place the answer is actually read, knows it
 * already - it drew the picker.
 *
 * And the catalogue belongs in one file. Every Polish string this product says
 * is in `orknux-ui/src/i18n/pl.ts`, where one person can read the whole of it
 * end to end and hear whether it is written in one voice. Half of them in a
 * `messages_pl.properties` on the other side of the repository would be half a
 * voice.
 *
 * ---------------------------------------------------------------------------
 * What goes on the wire
 *
 * `message` is unchanged: the English sentence, exactly as it always was. It is
 * what every existing client shows, what every test that pins an error text
 * still reads, and what the interface falls back to for anything it has no
 * translation for - so a refusal is never a bare code on a screen.
 *
 * `extensions.code` is the exception's own class name with `Exception` dropped:
 * `WorkspaceNameTakenException` becomes `WorkspaceNameTaken`. Derived rather
 * than declared, because a constant beside each of the two hundred and ninety
 * exception classes is two hundred and ninety chances to paste the wrong one,
 * and the class name is already unique, already meaningful, and already what a
 * reader greps for. Renaming a class changes the code, which is exactly right:
 * the catalogue then fails to match and falls back to English rather than
 * showing the wrong sentence.
 *
 * `extensions.arguments` is what the sentence interpolates, by name, and only
 * for an exception that opted in by implementing [Refusal]. Nothing else needs
 * it: a refusal with no values in it translates from its code alone.
 */
interface Refusal {

    /**
     * The values this refusal's sentence puts into itself, by name.
     *
     * Named and not positional. A translation reorders - Polish puts the object
     * where English puts the subject often enough that a list would be read
     * back-to-front - and a name in the catalogue is checkable by eye where an
     * index is not.
     */
    val arguments: Map<String, Any?>
}

/**
 * The code a client matches a refusal on: the class name, less `Exception`.
 *
 * Kotlin's `simpleName` is null for an anonymous object, which no exception
 * here is; the fallback keeps a caller from having to think about it.
 */
fun codeOf(exception: Throwable): String =
    (exception::class.simpleName ?: "Unknown").removeSuffix("Exception")

/**
 * The one shape every `…ExceptionResolver` answers in.
 *
 * A function rather than twelve copies of the same builder: the extensions are
 * the part that is easy to leave off, and a resolver that forgot them would be
 * a screenful of English in an otherwise Polish product with nothing to say why.
 */
fun refused(
    exception: Throwable,
    errorType: ErrorType,
    environment: DataFetchingEnvironment,
): GraphQLError {
    val extensions = buildMap<String, Any> {
        put("code", codeOf(exception))
        (exception as? Refusal)?.arguments
            ?.filterValues { it != null }
            ?.takeIf { it.isNotEmpty() }
            ?.let { put("arguments", it) }
    }

    return GraphQLError.newError()
        .errorType(errorType)
        .message(exception.message)
        .path(environment.executionStepInfo.path)
        .location(environment.field.sourceLocation)
        .extensions(extensions)
        .build()
}
