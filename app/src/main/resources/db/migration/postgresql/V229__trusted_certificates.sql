-- The certificate authorities this installation trusts, beyond the JVM's own.
--
-- Installation-wide and beside the proxy rules in spirit: both are about what
-- this server may reach on the way out, and neither belongs to a workspace.
-- Issue #322.
--
-- Nothing here is encrypted. A certificate authority's certificate is what a
-- server hands every client that connects; the key that signs with it is the
-- secret, and it never arrives here.
CREATE TABLE trusted_certificate
(
    id               BIGSERIAL PRIMARY KEY,
    -- What an administrator calls it. A list of PEM blocks is a list nobody can
    -- read: they are base64 and they all look alike.
    name             VARCHAR(120) NOT NULL,
    pem              TEXT         NOT NULL,
    -- What the certificate says about itself, read once when it was added, so a
    -- row can be recognised without parsing it again to draw the list.
    subject          VARCHAR(500) NOT NULL,
    expires_at       TIMESTAMPTZ,
    added_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    added_by         VARCHAR(120) NOT NULL
);

-- And the column V228 added to mcp_server, which this replaces.
--
-- V228 shipped nowhere, but it did run on a development database before the
-- decision moved: trusting an authority changes what every outbound connection
-- makes of a certificate, which is one decision for the installation rather
-- than one per server. It is left in place and undone here rather than edited,
-- because a migration that has been applied anywhere is a migration whose
-- checksum is already written down.
ALTER TABLE mcp_server DROP COLUMN IF EXISTS ca_certificate;
