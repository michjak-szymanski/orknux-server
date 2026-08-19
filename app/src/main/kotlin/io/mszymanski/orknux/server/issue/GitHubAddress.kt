package io.mszymanski.orknux.server.issue

import java.net.URI
import java.net.URISyntaxException

/**
 * A GitHub address read as GitHub reads it.
 *
 * A link to a pull request is the single most common thing anybody hangs on an
 * issue in a codebase's tracker, and shown as an address it is forty characters
 * of which four matter. `owner/repo#123` is what people say out loud, so it is
 * what the page shows.
 *
 * By the shape of the address and nothing else - this never asks GitHub
 * anything. Fetching the real title would want a token to configure, a network
 * call on the read path of every issue and somewhere to cache the answer, and
 * the first two of those are an operator's problem rather than a feature. So
 * `owner/repo#123` here means "this address is shaped like pull request 123",
 * not "a pull request called X exists": a number that was never opened reads
 * exactly the same, and a private repository reads the same to somebody who
 * cannot see it. That is a fair trade for a label, and would not be a fair
 * trade for anything the tracker acted on.
 *
 * `/issues/123` and `/pull/123` both come back as `owner/repo#123` on purpose.
 * GitHub numbers issues and pull requests from one counter and redirects
 * between the two paths, so the address a person copied does not reliably say
 * which kind it is, and claiming from the path alone would be claiming
 * something that is not known.
 *
 * Only github.com. A self-hosted GitHub Enterprise wears the installation's own
 * hostname, which nothing here can recognise, and guessing from a path that
 * merely looks like GitHub's would mislabel every tracker that copied the
 * layout.
 */
object GitHubAddress {

    private const val HOST = "github.com"

    /** A short sha is seven characters wherever GitHub prints one. */
    private const val SHORT_SHA = 7

    /**
     * First path segments that are GitHub's own pages rather than an account.
     *
     * `github.com/orgs/something/repositories` would otherwise read as the
     * repository `orgs/something`, which names nothing. Not a complete list of
     * GitHub's pages and does not need to be: what it has to catch is the ones
     * somebody plausibly pastes into an issue, and anything missed reads as a
     * slightly odd label rather than as a broken link.
     */
    private val NOT_AN_OWNER = setOf(
        "about", "apps", "codespaces", "collections", "contact", "explore", "features", "issues",
        "join", "login", "marketplace", "new", "notifications", "orgs", "pricing", "pulls",
        "search", "settings", "sponsors", "topics", "trending",
    )

    /**
     * What the address says it is, or null when it is not a GitHub one.
     *
     * Four shapes, because they are the four things anybody links to: a
     * repository, an issue or pull request in one, and a commit. Anything else
     * under a repository - a file, a branch, a release - comes back null and is
     * shown as the address it is, which is honest: `owner/repo` for a link into
     * a single file would name the wrong thing.
     */
    fun shortNameOf(url: String): String? {
        val parsed = try {
            URI(url.trim())
        } catch (_: URISyntaxException) {
            return null
        }

        val host = parsed.host?.lowercase()?.removePrefix("www.") ?: return null
        if (host != HOST) return null

        val path = parsed.path.orEmpty().split('/').filter { it.isNotEmpty() }
        if (path.size < 2) return null

        val owner = path[0]
        if (owner.lowercase() in NOT_AN_OWNER) return null
        // Clone addresses carry it and the web pages do not, so the same
        // repository would otherwise read two ways depending on where the
        // address was copied from.
        val repo = path[1].removeSuffix(".git")
        if (repo.isEmpty()) return null

        val repository = "$owner/$repo"
        if (path.size == 2) return repository
        if (path.size < 4) return null

        val what = path[2]
        val which = path[3]
        return when {
            // Trailing segments are allowed: /pull/12/files and /pull/12/commits
            // are the same pull request seen from a different tab.
            (what == "issues" || what == "pull") && which.isNumber() -> "$repository#$which"
            what == "commit" && which.isSha() -> "$repository@${which.take(SHORT_SHA).lowercase()}"
            else -> null
        }
    }

    private fun String.isNumber() = isNotEmpty() && all { it.isDigit() }

    /**
     * Hexadecimal, and long enough to be a sha rather than a word.
     *
     * Seven is what GitHub itself abbreviates to and what its own short links
     * carry, so anything shorter is not a commit that came from there.
     */
    private fun String.isSha() =
        length in SHORT_SHA..40 && all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
}
