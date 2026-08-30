package fxbot

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

private val SYMBOLS = DecimalFormatSymbols(Locale.US)

// DecimalFormat is mutable and not thread-safe, and Telegram updates are handled
// concurrently, so each call gets its own formatter rather than sharing one.
private fun grouped() = DecimalFormat("#,##0.##########", SYMBOLS).apply { roundingMode = RoundingMode.HALF_UP }
private fun twoDp() = DecimalFormat("#,##0.00", SYMBOLS).apply { roundingMode = RoundingMode.HALF_UP }

/** Accepts `1000`, `1 000`, `1,000.50`. Rejects anything not a positive plain number. */
fun parseAmount(raw: String): BigDecimal? {
    val cleaned = raw.trim().replace(" ", "").replace(",", "")
    if (cleaned.isEmpty()) return null
    if (!cleaned.all { it.isDigit() || it == '.' }) return null
    val value = runCatching { BigDecimal(cleaned) }.getOrNull() ?: return null
    return value.takeIf { it > BigDecimal.ZERO }
}

/** Stated amounts: grouped, trailing zeros stripped. */
fun formatAmount(v: BigDecimal): String = grouped().format(v.stripTrailingZeros())

/** Notionals: grouped, always two decimal places. */
fun formatNotional(v: BigDecimal): String = twoDp().format(v.setScale(2, RoundingMode.HALF_UP))
