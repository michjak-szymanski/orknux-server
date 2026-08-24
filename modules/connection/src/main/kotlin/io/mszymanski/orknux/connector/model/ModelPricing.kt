package io.mszymanski.orknux.connector.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * What a number of tokens costs, at what a model is recorded as charging.
 *
 * One place, because two of them would disagree. The metrics card costs a
 * thirty-day window and a chat costs a single answer, and the moment those are
 * worked out separately somebody's month stops adding up to the answers in it.
 *
 * The prices are per million tokens and are optional on the model, so the answer
 * is optional too: null means nobody recorded what this model charges, which is
 * not the same as free. A caller shows nothing where it gets null - a zero would
 * be a claim about money, and the one it would make is wrong.
 *
 * A model with one of the two prices and not the other is costed on the half it
 * has. That is deliberately not null: half a price is somebody part way through
 * filling the form in, and the number it gives is a floor rather than a
 * fabrication.
 */
object ModelPricing {

    /**
     * The scale a window is reported at. Two places, because a month of a team's
     * chat is dollars and cents and the fractions under that are noise.
     */
    const val WINDOW_SCALE = 2

    /**
     * The scale a single answer is reported at.
     *
     * Four, because one answer at ordinary prices is a fraction of a cent:
     * rounded to the window's two places every line in a chat would read $0.00,
     * which is the zero this feature exists not to print.
     */
    const val ANSWER_SCALE = 4

    private val MILLION: BigDecimal = BigDecimal(1_000_000)

    fun cost(model: LlmModel, inputTokens: Long, outputTokens: Long, scale: Int = ANSWER_SCALE): BigDecimal? =
        cost(model.inputCostPerMillion, model.outputCostPerMillion, inputTokens, outputTokens, scale)

    /** The same arithmetic for a caller holding the two prices rather than the model. */
    fun cost(
        inputCostPerMillion: BigDecimal?,
        outputCostPerMillion: BigDecimal?,
        inputTokens: Long,
        outputTokens: Long,
        scale: Int = ANSWER_SCALE,
    ): BigDecimal? {
        if (inputCostPerMillion == null && outputCostPerMillion == null) return null

        val perMillion = { tokens: Long, price: BigDecimal? ->
            price?.multiply(BigDecimal(tokens))?.divide(MILLION, scale, RoundingMode.HALF_UP) ?: BigDecimal.ZERO
        }
        return perMillion(inputTokens, inputCostPerMillion).add(perMillion(outputTokens, outputCostPerMillion))
    }
}
