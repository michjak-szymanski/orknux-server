package io.mszymanski.orknux.server.workspace

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * The narrow door: what a command's *output* is stripped of, and what it keeps.
 *
 * `AuditRedaction.redact` is for a command line and may over-redact, because an
 * audit row nobody reads back costs nothing when it says `***` too often. This
 * one runs over what a tool returned, and that is handed straight to a model as
 * the answer to the lookup it just made. So the negative half below is not a
 * nicety - it is the functional half. A build log whose `cannot find symbol`
 * line came back as asterisks is a model that cannot fix the build, and a
 * `--help` dump full of `***` is a model that cannot call the program.
 *
 * The positive half is deliberately short, and short is the claim being made:
 * only values that are a credential on sight. Everything else in output stays
 * in the transcript, which is written down on `redactObvious` rather than
 * tested here, because there is no test for a thing that is not done.
 */
class ObviousRedactionTest {

    @Test
    fun `a github token printed by a command is replaced`() {
        val printed = "Token: ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg\nScopes: repo, workflow"

        assertThat(AuditRedaction.redactObvious(printed))
            .isEqualTo("Token: ***\nScopes: repo, workflow")
    }

    @Test
    fun `an aws access key id in an env dump is replaced`() {
        // The value goes because of its shape. The name does not: `redact`
        // would have taken this on the name alone, and this door reads no names
        // at all - which is exactly why it leaves a build log alone.
        assertThat(AuditRedaction.redactObvious("AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE"))
            .isEqualTo("AWS_ACCESS_KEY_ID=***")
    }

    @Test
    fun `a jwt in output is replaced`() {
        val printed = "id_token eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkFsaWNlIn0." +
            "dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertThat(AuditRedaction.redactObvious(printed)).isEqualTo("id_token ***")
    }

    @Test
    fun `a private key a command printed is replaced whole`() {
        val printed = "reading ~/.ssh/id_rsa\n" +
            "-----BEGIN OPENSSH PRIVATE KEY-----\n" +
            "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAABlwAAAAdz\n" +
            "c2gtcnNhAAAAAwEAAQAAAYEAvJ8kZmFrZWtleWZvcmF0ZXN0AAAAAAAAAAAAAAAA\n" +
            "-----END OPENSSH PRIVATE KEY-----\n" +
            "1 file read"

        assertThat(AuditRedaction.redactObvious(printed))
            .isEqualTo("reading ~/.ssh/id_rsa\n***\n1 file read")
    }

    /**
     * The half that would be a regression rather than a leak.
     *
     * Every line here carries a word the full rule set treats as a credential
     * name - `password`, `--token`, `key=` - and every one has to come back
     * byte-identical, because the model reads it to decide what to do next.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            // A build log. `password` is a variable the compiler is complaining
            // about, and that complaint is what the next turn has to act on.
            "[INFO] Scanning for projects...\n[ERROR] Db.kt:14:22: cannot find symbol: password\n[INFO] BUILD FAILURE",
            // A --help dump. Every flag it documents is a flag, not a value.
            "Usage: deploy [OPTIONS]\n  --token TEXT  the API token\n  --password TEXT  the registry password",
            // A config listing, which is the shape `redact` mangles worst.
            "spring.datasource.password=hunter2\nserver.port=8080\norknux.key=default",
            // The commonest line in any dump there is.
            "key=value",
            // A test report, which says the words out loud in its own names.
            "[ERROR] AuditRedactionTest.a password in a url is removed:31",
            // Prose a tool wrote about what it did.
            "Rotated the deploy token and wrote the new password to the vault.",
        ],
    )
    fun `ordinary command output comes back exactly as it went in`(printed: String) {
        assertThat(AuditRedaction.redactObvious(printed)).isEqualTo(printed)
    }

    @Test
    fun `running it twice gives the same answer as running it once`() {
        val once = AuditRedaction.redactObvious("Token: ghp_ZmFrZXRva2VuZm9yYXRlc3Q0Mg")

        assertThat(AuditRedaction.redactObvious(once)).isEqualTo(once)
    }

    @Test
    fun `the two doors differ on exactly this`() {
        // Pinned as a pair, because the whole design is that they are not the
        // same answer. If they ever agree here, one of them has drifted.
        val line = "--token abc123 --password hunter2"

        assertThat(AuditRedaction.redact(line)).isEqualTo("--token *** --password ***")
        assertThat(AuditRedaction.redactObvious(line)).isEqualTo(line)
    }
}
