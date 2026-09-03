package io.mszymanski.orknux.server.library

/**
 * Which published version a dependency's range means.
 *
 * **Why this is here at all, given that a range is refused everywhere else.**
 * What an administrator types is still an exact version and always will be: a
 * library is the answer to "what code is running in this installation", and a
 * specification that resolves differently tomorrow is not an answer. That rule
 * is about *what somebody asks for*. A package's own `dependencies` are not
 * asked for by anybody here — they are published, they are ranges because npm
 * makes them ranges, and a bundle that needs `ms` cannot ask a person which `ms`.
 * Issue #319.
 *
 * So the range is resolved **once**, here, against the versions the registry has
 * published, and the version it resolved to is written down on the row beside
 * the package that wanted it. The bundle is then an artefact with a bill of
 * materials rather than a black box, which is the property the provenance
 * columns exist for: it says `debug@4.3.4 with ms@2.1.3`, and anybody holding
 * the same two packages can build the same thing and compare.
 *
 * **This is a subset of npm's grammar and says so.** The forms published
 * packages actually use are here — an exact version, `^`, `~`, `x`/`*`, the
 * comparators, a hyphen range, and `||` between any of those. What is not here
 * is refused by name rather than guessed at, because a range this misreads is a
 * dependency silently resolved to the wrong code.
 */
object NpmVersions {

    /**
     * The highest published version the range allows, or null.
     *
     * Highest rather than lowest, which is npm's own answer and the one a person
     * checking this against their `node_modules` will expect. A prerelease is
     * never chosen unless the range names one, which is also npm's rule: `^1.0.0`
     * does not mean `2.0.0-beta.1`, and a resolver that thought it did would
     * quietly install somebody's unfinished work.
     */
    fun best(range: String, published: Collection<String>): String? {
        val wanted = parse(range)
        val prereleases = wanted.any { comparators -> comparators.any { it.version.prerelease != null } }

        return published
            .mapNotNull { version -> parsed(version)?.let { version to it } }
            .filter { (_, semver) -> prereleases || semver.prerelease == null }
            .filter { (_, semver) -> wanted.any { comparators -> comparators.all { it.allows(semver) } } }
            .maxWithOrNull(compareBy(ORDER) { it.second })
            ?.first
    }

    /** Whether one exact version satisfies a range, which is [best] of one. */
    fun allows(range: String, version: String): Boolean = best(range, listOf(version)) != null

    /**
     * A range as alternatives, each of which is a list of comparators that all
     * have to hold.
     *
     * `^1.2.3 || >=2.0.0 <3` is two alternatives, the first of one comparator and
     * the second of two — which is exactly npm's own reading, and the reason the
     * shape is a list of lists rather than anything cleverer.
     */
    private fun parse(range: String): List<List<Comparator>> =
        range.split("||").map { alternative -> comparators(alternative.trim()) }

    private fun comparators(alternative: String): List<Comparator> {
        val written = alternative.trim().ifEmpty { "*" }

        // `1.2.3 - 2.3.4`, which is both bounds in one and has to be read before
        // the pieces are split on whitespace.
        val hyphen = HYPHEN.matchEntire(written)
        if (hyphen != null) {
            val (from, to) = hyphen.destructured
            return listOf(
                Comparator(AT_LEAST, requireNotNull(parsed(fleshed(from)))),
                Comparator(BELOW, upperOf(to)),
            )
        }

        return written.split(WHITESPACE).filter { it.isNotBlank() }.flatMap { one(it) }
    }

    /** One written comparator as the one or two bounds it stands for. */
    private fun one(written: String): List<Comparator> {
        val bare = written.removePrefix("v").trim()
        if (bare.isEmpty() || bare == "*" || bare == "x" || bare == "X") return emptyList()

        for ((sign, direction) in SIGNS) {
            if (bare.startsWith(sign)) {
                val version = parsed(fleshed(bare.removePrefix(sign)))
                    ?: throw LibraryRangeUnreadableException(written)
                return listOf(Comparator(direction, version))
            }
        }

        /*
         * `^1.2.3`, `~1.2.3` and a bare `1.2` are all the same shape - a floor
         * and a ceiling - and which ceiling depends on two things: which sign it
         * was written with, and how much of the version was actually written.
         * A bare `1.2.3` is neither: it is one version, and reading it as a range
         * would install 1.2.9 where somebody wrote 1.2.3.
         */
        val sign = listOf("^", "~", "=").firstOrNull { bare.startsWith(it) }.orEmpty()
        val floor = parsed(fleshed(bare.removePrefix(sign))) ?: throw LibraryRangeUnreadableException(written)
        val specified = specified(bare.removePrefix(sign))

        if (sign != "^" && sign != "~" && specified == 3) return listOf(Comparator(EXACTLY, floor))
        return listOf(Comparator(AT_LEAST, floor), Comparator(BELOW, ceiling(sign, specified, floor)))
    }

    /**
     * How much of a version was actually written: `1.2` is two, `1.2.x` is two.
     *
     * An `x` is not a number somebody wrote, it is the place where they stopped,
     * so it counts as left off — which is what makes `1.2.x` and `1.2` the same
     * range, as they are in npm.
     */
    private fun specified(written: String): Int {
        val numbers = written.trim().removePrefix("v").substringBefore('-').substringBefore('+').split('.')
        val at = numbers.indexOfFirst { it.isEmpty() || it == "x" || it == "X" || it == "*" }
        return if (at < 0) minOf(numbers.size, 3) else at
    }

    /**
     * What a version allows above it, given the sign it was written with.
     *
     * `^1.2.3` is anything under `2.0.0`, and `^0.2.3` under `0.3.0`, because a
     * zero major is npm's convention for "the minor is the breaking one" - and
     * `^0.0.3` allows nothing but patches of nothing, which is `0.0.4`. `~1.2.3`
     * is under `1.3.0`; `~1` is under `2.0.0`. A bare `1.2` is `>=1.2.0 <1.3.0`
     * and a bare `1` is under `2.0.0` - the ceiling follows what was left off.
     */
    private fun ceiling(sign: String, specified: Int, floor: Semver): Semver = when {
        sign == "^" && floor.major > 0 -> Semver(floor.major + 1, 0, 0)
        sign == "^" && specified <= 1 -> Semver(1, 0, 0)
        sign == "^" && floor.minor > 0 -> Semver(0, floor.minor + 1, 0)
        sign == "^" && specified <= 2 -> Semver(0, 1, 0)
        sign == "^" -> Semver(0, 0, floor.patch + 1)

        specified <= 1 -> Semver(floor.major + 1, 0, 0)
        else -> Semver(floor.major, floor.minor + 1, 0)
    }

    /** The open end of a hyphen range: `- 2.3` means everything under `2.4.0`. */
    private fun upperOf(written: String): Semver {
        val parts = specified(written)
        val version = parsed(fleshed(written.trim())) ?: throw LibraryRangeUnreadableException(written)
        return when (parts) {
            1 -> Semver(version.major + 1, 0, 0)
            2 -> Semver(version.major, version.minor + 1, 0)
            // A whole version written out is inclusive, so the exclusive bound
            // above it is the next patch.
            else -> Semver(version.major, version.minor, version.patch + 1, version.prerelease)
        }
    }

    /** `1`, `1.2` and `1.2.x` as `1.0.0` and `1.2.0`: the floor of what was written. */
    private fun fleshed(written: String): String {
        val bare = written.trim().removePrefix("v")
        val prerelease = bare.substringAfter('-', "")
        val numbers = bare.substringBefore('-').substringBefore('+')
            .split('.')
            .map { if (it == "x" || it == "X" || it == "*" || it.isEmpty()) "0" else it }
        val three = (numbers + listOf("0", "0")).take(3).joinToString(".")
        return if (prerelease.isEmpty()) three else "$three-$prerelease"
    }

    private fun parsed(version: String): Semver? {
        val matched = SEMVER.matchEntire(version.trim().removePrefix("v")) ?: return null
        val (major, minor, patch, prerelease) = matched.destructured
        return Semver(
            major.toIntOrNull() ?: return null,
            minor.toIntOrNull() ?: return null,
            patch.toIntOrNull() ?: return null,
            prerelease.ifEmpty { null },
        )
    }

    private data class Semver(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val prerelease: String? = null,
    )

    private data class Comparator(val direction: Int, val version: Semver) {
        fun allows(other: Semver): Boolean {
            val order = ORDER.compare(other, version)
            return when (direction) {
                AT_LEAST -> order >= 0
                ABOVE -> order > 0
                BELOW -> order < 0
                AT_MOST -> order <= 0
                else -> order == 0
            }
        }
    }

    private const val EXACTLY = 0
    private const val AT_LEAST = 1
    private const val ABOVE = 2
    private const val BELOW = 3
    private const val AT_MOST = 4

    /** Longest sign first, so `>=` is never read as `>`. */
    private val SIGNS = listOf(">=" to AT_LEAST, "<=" to AT_MOST, ">" to ABOVE, "<" to BELOW)

    /**
     * Semver's own order, prereleases included.
     *
     * A version with a prerelease sorts below the same version without one, and
     * two prereleases compare piece by piece — numbers as numbers, so `beta.9`
     * is below `beta.10` rather than above it, which is the whole reason this is
     * not a string comparison.
     */
    private val ORDER = java.util.Comparator<Semver> { left, right ->
        var order = left.major - right.major
        if (order == 0) order = left.minor - right.minor
        if (order == 0) order = left.patch - right.patch
        if (order != 0) return@Comparator order

        when {
            left.prerelease == null && right.prerelease == null -> 0
            left.prerelease == null -> 1
            right.prerelease == null -> -1
            else -> prereleases(left.prerelease, right.prerelease)
        }
    }

    private fun prereleases(left: String, right: String): Int {
        val ours = left.split('.')
        val theirs = right.split('.')
        for (at in 0 until maxOf(ours.size, theirs.size)) {
            val one = ours.getOrNull(at) ?: return -1
            val other = theirs.getOrNull(at) ?: return 1
            val numbers = one.toIntOrNull() to other.toIntOrNull()
            val order = when {
                numbers.first != null && numbers.second != null -> numbers.first!! - numbers.second!!
                // A numeric piece is always below an alphanumeric one.
                numbers.first != null -> -1
                numbers.second != null -> 1
                else -> one.compareTo(other)
            }
            if (order != 0) return order
        }
        return 0
    }

    private val SEMVER = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?")

    private val HYPHEN = Regex("([0-9xX*][^\\s]*)\\s+-\\s+([0-9xX*][^\\s]*)")

    private val WHITESPACE = Regex("\\s+")
}

/**
 * A dependency's range is written in something this does not read.
 *
 * Refused rather than approximated, and it names the range. A range read wrongly
 * is a dependency resolved to the wrong code, which is the one failure a bundle
 * must not have — and the way out is the way out of everything else here: build
 * the bundle where bundles are built, and upload the one file.
 */
class LibraryRangeUnreadableException(range: String) : RuntimeException(
    "\"$range\" is a version range this cannot read, so what it means cannot be established. " +
        "Build the bundle elsewhere and upload the one file.",
)
