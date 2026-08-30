package fxbot

import java.util.Currency

/** Which way a request runs, always relative to the pair's base currency. */
enum class Side { BID, OFFER }

/** What the person typed, before it is turned into a side. */
enum class Verb { SELL, BUY }

data class CurrencyPair(val base: String, val quote: String) {
    fun contains(ccy: String) = ccy == base || ccy == quote
    fun other(ccy: String) = if (ccy == base) quote else base
    override fun toString() = "$base/$quote"
}

/**
 * You are offering exactly when you hand over the base currency:
 * selling the base, or buying the quote (which you pay for in base).
 */
fun sideFor(verb: Verb, statedCurrency: String, pair: CurrencyPair): Side {
    val givesBase = if (verb == Verb.SELL) statedCurrency == pair.base else statedCurrency == pair.quote
    return if (givesBase) Side.OFFER else Side.BID
}

/** Upper-cases and checks the code is ISO 4217. Returns null if it is not. */
fun parseCurrency(raw: String): String? {
    val code = raw.trim().uppercase()
    if (code.length != 3) return null
    return runCatching { Currency.getInstance(code).currencyCode }.getOrNull()
}

/** Offer gives the base currency; Bid gives the quote. */
fun Side.giveCurrency(pair: CurrencyPair): String =
    if (this == Side.OFFER) pair.base else pair.quote

/**
 * The verb the author used, recovered from what they gave and what they quoted.
 * Quoting the currency you hand over is "sell"; quoting the other one is "buy".
 */
fun verbFor(side: Side, statedCurrency: String, pair: CurrencyPair): Verb =
    if (statedCurrency == side.giveCurrency(pair)) Verb.SELL else Verb.BUY
