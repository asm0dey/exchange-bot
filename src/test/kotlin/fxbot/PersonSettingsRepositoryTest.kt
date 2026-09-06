package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

private fun people(name: String): Pair<PersonSettingsRepository, org.jetbrains.exposed.v1.jdbc.Database> {
    val ds = memDataSource(name)
    migrate(ds)
    return PersonSettingsRepository(ds, testCrypto()) to connectExposed(ds)
}

class PersonSettingsRepositoryTest : StringSpec({
    "someone the bot has never been told about gets the twenty percent default" {
        val (r, _) = people("persondefault")
        r.get(7L).tolerancePct shouldBe 20
    }
    "looking someone up does not write a row about them" {
        val (r, db) = people("personnowrite")
        r.get(7L)
        transaction(db) { PersonSettingsTable.selectAll().count() } shouldBe 0L
    }
    "a saved tolerance round-trips" {
        val (r, _) = people("personsave")
        r.save(PersonSettings(7L, 5))
        r.get(7L).tolerancePct shouldBe 5
    }
    "tolerances are per person" {
        val (r, _) = people("personper")
        r.save(PersonSettings(7L, 5))
        r.get(8L).tolerancePct shouldBe 20
    }
    "saving twice updates in place" {
        val (r, db) = people("personresave")
        r.save(PersonSettings(7L, 5))
        r.save(PersonSettings(7L, 40))
        r.get(7L).tolerancePct shouldBe 40
        transaction(db) { PersonSettingsTable.selectAll().count() } shouldBe 1L
    }
    "the tolerance command bounds the percentage between one and a hundred" {
        val (r, _) = people("personbounds")
        r.setTolerance(7L, "1")
        r.get(7L).tolerancePct shouldBe 1
        r.setTolerance(7L, "100")
        r.get(7L).tolerancePct shouldBe 100
        r.setTolerance(7L, "0") shouldContain "between"
        r.setTolerance(7L, "101") shouldContain "between"
        r.setTolerance(7L, "lots") shouldContain "between"
        r.get(7L).tolerancePct shouldBe 100 // none of the three rejections wrote anything
    }
    "forgetting drops the row" {
        val (r, _) = people("persondelete")
        r.save(PersonSettings(7L, 5))
        r.save(PersonSettings(9L, 15))
        r.delete(7L)
        r.get(7L).tolerancePct shouldBe 20
        r.get(9L).tolerancePct shouldBe 15
    }
})
