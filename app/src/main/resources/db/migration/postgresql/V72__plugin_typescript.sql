-- What a plugin was written in, beside the JavaScript that runs.
--
-- The sandbox runs JavaScript and this server has no compiler, so `source` stays
-- what it always was: the compiled code, and the only thing that is ever evaluated.
--
-- This is for getting it back out. A plugin can be downloaded at any time, and
-- handing somebody the compiler's output when they wrote TypeScript is handing them
-- something they did not write — annotations gone, and no way to get them back.
--
-- Null for a plugin uploaded as JavaScript, which is most of them: then the source
-- and the download are the same thing, and there is nothing else to keep.
ALTER TABLE plugin
    ADD COLUMN typescript TEXT;
