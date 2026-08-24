package io.mszymanski.orknux.connector

/**
 * One thing in this module reading a workspace variable for its credential.
 *
 * The id and the name, and deliberately nothing else. A connection row, an MCP
 * server row and a provider row are all credential holders that this module has
 * no business handing out — that argument is written where each `…Reading` method
 * is — and it is untouched here: what crosses the boundary is still two fields.
 *
 * The id is the field that was missing. `VariableAPI` asked these three
 * questions to build a refusal, got names, and joined them into a sentence, so
 * being told *"the connection Slack"* left the reader to go and find the
 * connection by hand. The id is what turns that clause into somewhere to go —
 * see [Dependant][io.mszymanski.orknux.server.dependency.Dependant], which is
 * what the app wraps these in.
 *
 * In the package root rather than in `connection`, because `model` answers the
 * same question about a provider and neither of the two owns the other.
 */
data class CredentialReader(val id: Long, val name: String)
