-- Which spelling a library's stored file is written in, and nothing more.
--
-- A library is one self-contained module, and for a release that also meant one
-- self-contained *ES* module: a package publishing only a CommonJS build was
-- refused. That rule was wider than the reason behind it. A CommonJS file that
-- requires nothing is a self-contained module with a different spelling, and the
-- translation into one is mechanical - it is given the `module` and `exports` its
-- code expects, and what it leaves on them becomes the default export.
--
-- This column says which of the two the text in `source` is. It is a statement
-- about how that text is run, never about where it came from.
--
-- The decision worth writing down is why the wrapped text is not what is stored.
-- `origin_integrity` is the registry's own hash of the archive the file came out
-- of, verified when it arrived, and `sha256` is over the stored text. Store the
-- wrapped file and neither is reproducible by anybody else holding the same
-- package: the row would go on naming a package, a version and a hash while
-- holding something this server invented, which is exactly the claim these
-- columns exist to make checkable. So the file is stored as it arrived, byte for
-- byte, the wrapper is put round it at the moment it is evaluated, and this
-- column is how the run-time path knows to - without re-reading a four-megabyte
-- bundle with a regular expression on every call.
--
-- Existing rows are ESM. Nothing else could have been loaded before this.
ALTER TABLE script_library
    ADD COLUMN source_format VARCHAR(16) NOT NULL DEFAULT 'ESM';

ALTER TABLE script_library
    ADD CONSTRAINT ck_script_library_source_format CHECK (source_format IN ('ESM', 'COMMONJS'));
