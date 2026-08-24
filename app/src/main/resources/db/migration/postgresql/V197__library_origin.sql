-- Where a library came from, for the ones that were fetched rather than chosen.
--
-- A library is still a stored artefact: the file in `source` is what runs, the
-- sandbox still has no network, and an installation configured without a registry
-- still has only the upload. What changed in #265 is how the file can arrive - a
-- package named by an administrator, fetched once, on the server, into this row.
--
-- These columns are the price of that, and they are not decoration. A file
-- somebody chose is a file this installation can say nothing further about; a file
-- it fetched has to be able to answer which package, which version, from where and
-- what it hashed to, or "what code is running in here" has quietly become "some
-- version of something, once".
--
-- `origin` is stated rather than inferred from whether the columns beside it are
-- filled in. A registry row that lost its provenance would otherwise read as an
-- upload, and an upload is exactly the row that claims nothing.
ALTER TABLE script_library
    ADD COLUMN origin           VARCHAR(16) NOT NULL DEFAULT 'UPLOAD',
    -- The npm package, scope and all. npm's own limit on a name is 214.
    ADD COLUMN origin_package   VARCHAR(214),
    -- Exactly one version, as the registry resolved it. A range and `latest` are
    -- refused before anything is fetched: a specification that resolves
    -- differently tomorrow is not an answer to what is running today.
    ADD COLUMN origin_version   VARCHAR(64),
    -- The file that was downloaded, so the fetch can be repeated and compared.
    ADD COLUMN origin_url       VARCHAR(500),
    -- What the registry said that file hashes to, verified against what arrived.
    -- Kept in the registry's own spelling - `sha512-...` - because what it is good
    -- for is being compared with the same claim made somewhere else. The sha256
    -- column beside it is the other half, and is over the stored text.
    ADD COLUMN origin_integrity VARCHAR(160),
    -- Which file inside the package is the one that runs. A package ships several
    -- builds; without this the row names a version and still cannot say what it
    -- holds.
    ADD COLUMN origin_entry     VARCHAR(255);

ALTER TABLE script_library
    ADD CONSTRAINT ck_script_library_origin CHECK (origin IN ('UPLOAD', 'REGISTRY'));
