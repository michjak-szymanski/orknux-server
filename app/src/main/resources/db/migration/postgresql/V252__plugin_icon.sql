-- The face an installed plugin wears.
--
-- Copied in at install rather than pointed at: the marketplace hosts the SVG,
-- but a plugin that is loaded here has to draw on a screen whose installation
-- may never reach the marketplace again -- an air-gapped one, or simply one
-- whose catalog has moved on. So the bytes come across once and live with the
-- row, the way the plugin's own source does.
--
-- Text rather than bytea because an SVG is text, and an emoji stands as
-- itself for a catalog that offers one instead.
ALTER TABLE plugin
    ADD COLUMN icon TEXT;
