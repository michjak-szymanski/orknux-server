package io.mszymanski.orknux.server.security

import io.mszymanski.orknux.connector.connection.ConnectionCredentials
import io.mszymanski.orknux.connector.security.SecretCipher
import io.mszymanski.orknux.connector.security.SecretReferences
import io.mszymanski.orknux.connector.security.SecretVariables

/**
 * A workspace that keeps no variables at all, for the tests that build a
 * connector component by hand.
 *
 * Every credential in those tests is the row's own plain-text copy, so the
 * lookup answers null and the cipher is only ever asked whether a value is in an
 * envelope. Written once because it is four constructor arguments in five
 * places, and a test that got it subtly wrong would be a test resolving
 * credentials differently from the application.
 */
fun ownCredentialsOnly(): SecretReferences = SecretReferences(SecretVariables { _, _ -> null }, SecretCipher(""))

/** The same, as the thing that reads a connection's or an MCP server's credential. */
fun plainCredentials(): ConnectionCredentials = ConnectionCredentials(ownCredentialsOnly())
