package io.mszymanski.orknux.server.library

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * What a published dependency range resolves to.
 *
 * Issue #319. This is the one part of bundling that decides *which code* ends up
 * inside somebody's library without anybody typing it, so it is the part worth
 * pinning hardest: a range read a little wrongly is a dependency quietly
 * resolved to the wrong version, and nothing downstream would notice.
 *
 * The cases are the ones npm's own grammar turns on rather than a sample of
 * plausible strings — the zero-major rule that makes `^0.2.3` mean something
 * different from `^1.2.3`, a bare version being one version rather than a range,
 * and prereleases being invisible until a range asks for one.
 */
class NpmVersionsTest {

    private val published = listOf(
        "0.0.1", "0.0.2", "0.1.0", "0.2.3", "0.2.9", "0.3.0",
        "1.0.0", "1.2.3", "1.2.9", "1.3.0", "1.9.9",
        "2.0.0-beta.2", "2.0.0-beta.10", "2.0.0", "2.1.4", "3.0.0",
    )

    @Test
    fun `a caret takes the highest of the same major`() {
        assertThat(NpmVersions.best("^1.2.3", published)).isEqualTo("1.9.9")
    }

    /**
     * The rule that is not the same as the one above it.
     *
     * A zero major is npm's convention for "the minor is the breaking one", so
     * `^0.2.3` stops below `0.3.0`. Read as the caret above it would install
     * `0.3.0` into a package that said it could not take it.
     */
    @Test
    fun `a caret on a zero major stops at the minor`() {
        assertThat(NpmVersions.best("^0.2.3", published)).isEqualTo("0.2.9")
    }

    @Test
    fun `a caret on a zero minor stops at the patch`() {
        assertThat(NpmVersions.best("^0.0.1", published)).isEqualTo("0.0.1")
    }

    @Test
    fun `a tilde stops at the minor`() {
        assertThat(NpmVersions.best("~1.2.3", published)).isEqualTo("1.2.9")
    }

    /** One version, not a range. `1.2.3` means 1.2.3 and never 1.2.9. */
    @Test
    fun `a bare version is exactly that version`() {
        assertThat(NpmVersions.best("1.2.3", published)).isEqualTo("1.2.3")
    }

    @Test
    fun `a partly written version is the range it leaves open`() {
        assertThat(NpmVersions.best("1.2", published)).isEqualTo("1.2.9")
        assertThat(NpmVersions.best("1.2.x", published)).isEqualTo("1.2.9")
        assertThat(NpmVersions.best("1", published)).isEqualTo("1.9.9")
    }

    @Test
    fun `comparators hold together`() {
        assertThat(NpmVersions.best(">=1.0.0 <2.0.0", published)).isEqualTo("1.9.9")
        assertThat(NpmVersions.best(">1.2.3 <=1.3.0", published)).isEqualTo("1.3.0")
    }

    @Test
    fun `alternatives are tried and the highest of any of them wins`() {
        assertThat(NpmVersions.best("^1.0.0 || ^3.0.0", published)).isEqualTo("3.0.0")
    }

    @Test
    fun `a hyphen range covers both ends`() {
        assertThat(NpmVersions.best("1.0.0 - 1.3.0", published)).isEqualTo("1.3.0")
        assertThat(NpmVersions.best("1.0.0 - 1.2", published)).isEqualTo("1.2.9")
    }

    @Test
    fun `anything means the highest published`() {
        assertThat(NpmVersions.best("*", published)).isEqualTo("3.0.0")
        assertThat(NpmVersions.best("", published)).isEqualTo("3.0.0")
    }

    /**
     * The one that would quietly install somebody's unfinished work.
     *
     * `^2.0.0` does not mean `2.0.0-beta.10` — a prerelease is only ever chosen
     * where the range itself names one, which is npm's rule and the only one that
     * makes a published `^` safe to resolve without asking.
     */
    @Test
    fun `a prerelease is invisible unless the range asks for one`() {
        assertThat(NpmVersions.best("^2.0.0", published)).isEqualTo("2.1.4")
        assertThat(NpmVersions.best(">=2.0.0-beta.1 <2.0.0", published)).isEqualTo("2.0.0-beta.10")
    }

    /** `beta.10` is above `beta.2`, which a string comparison gets backwards. */
    @Test
    fun `prereleases are ordered piece by piece`() {
        assertThat(NpmVersions.best(">=2.0.0-beta.1 <2.0.0", listOf("2.0.0-beta.2", "2.0.0-beta.10")))
            .isEqualTo("2.0.0-beta.10")
    }

    @Test
    fun `a range nothing published satisfies answers nothing`() {
        assertThat(NpmVersions.best("^9.0.0", published)).isNull()
    }

    /**
     * What it cannot read, it refuses.
     *
     * The alternative is approximating, and an approximated range is a
     * dependency resolved to code nobody chose.
     */
    @Test
    fun `something it cannot read is refused rather than approximated`() {
        assertThatThrownBy { NpmVersions.best("workspace:^", published) }
            .isInstanceOf(LibraryRangeUnreadableException::class.java)
            .hasMessageContaining("workspace:^")
    }

    @Test
    fun `an exact version can be checked against a range`() {
        assertThat(NpmVersions.allows("^1.2.0", "1.4.0")).isTrue()
        assertThat(NpmVersions.allows("^1.2.0", "2.0.0")).isFalse()
    }
}
