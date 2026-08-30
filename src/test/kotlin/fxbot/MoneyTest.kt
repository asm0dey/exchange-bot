package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.math.BigDecimal

class MoneyTest : StringSpec({
    "parses plain and grouped amounts" {
        parseAmount("1000") shouldBe BigDecimal("1000")
        parseAmount("1 000") shouldBe BigDecimal("1000")
        parseAmount("1,000.50") shouldBe BigDecimal("1000.50")
        parseAmount("0.5") shouldBe BigDecimal("0.5")
    }
    "rejects amounts that are not positive numbers" {
        parseAmount("0").shouldBeNull()
        parseAmount("-5").shouldBeNull()
        parseAmount("abc").shouldBeNull()
        parseAmount("").shouldBeNull()
        parseAmount("1e9").shouldBeNull()
    }
    "formats stated amounts with grouping and no trailing zeros" {
        formatAmount(BigDecimal("1000")) shouldBe "1,000"
        formatAmount(BigDecimal("1000.00")) shouldBe "1,000"
        formatAmount(BigDecimal("95000")) shouldBe "95,000"
        formatAmount(BigDecimal("1000.50")) shouldBe "1,000.5"
    }
    "formats notionals at two decimal places" {
        formatNotional(BigDecimal("1000")) shouldBe "1,000.00"
        formatNotional(BigDecimal("950.1876")) shouldBe "950.19"
    }
})
