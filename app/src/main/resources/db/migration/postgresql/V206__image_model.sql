-- A sixth kind of model: one that draws.
--
-- The fourth beyond the ordinary chat model, and it follows the three that came
-- before it rather than inventing anything. V59 added a model that listens, V73
-- one that reads aloud; this one is text in, a picture out.
--
-- What it is called is text-to-image; what the column says is IMAGE, because the
-- kind names what comes out and the other four already do.
ALTER TABLE llm_model
    DROP CONSTRAINT ck_llm_model_kind;

ALTER TABLE llm_model
    ADD CONSTRAINT ck_llm_model_kind CHECK (
        kind IN ('CHAT', 'EMBEDDING', 'COMPLETION', 'TRANSCRIPTION', 'SPEECH', 'IMAGE')
    );

-- What one picture costs.
--
-- A column of its own rather than a reuse of the two prices beside it, because
-- these providers do not bill this per token: OpenAI's image models are priced
-- per image at a size, and a per-million-token figure is not a translation of
-- that at any exchange rate. Filling the token prices in for an image model
-- would make the metrics card cost a month of drawing at zero, since an image
-- call reports no tokens at all -- which is the "$0.00 for something that cost
-- real money" this column exists to avoid.
--
-- Null means nobody recorded it, which is not the same as free: the estimate is
-- reported as absent, exactly as ModelPricing already does for a chat model with
-- no prices on it.
--
-- Same precision as the two token prices so the three read alike on the form,
-- and because four decimal places is enough for a provider that charges less
-- than a cent a picture.
ALTER TABLE llm_model
    ADD COLUMN image_cost_per_image NUMERIC(12, 4);

-- Which model a workspace draws with.
--
-- Per workspace, on the same card and for the same reason the speech and
-- transcription models are: it is a fact about what this installation can reach,
-- not a choice to be made per message. Null means the picture button is not
-- offered in a chat at all, which is right for an installation with nothing to
-- draw with -- better than a button that fails on every press.
ALTER TABLE workspace
    ADD COLUMN image_model_id BIGINT REFERENCES llm_model (id) ON DELETE SET NULL;
