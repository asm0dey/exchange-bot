package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.TimeZone

/**
 * Guards the zone pin itself (`applicationDefaultJvmArgs` / `tasks.test` in
 * build.gradle.kts), not a round-trip of any particular Instant through Exposed's
 * InstantColumnType. That coupling to the JVM default zone is real and stays in
 * place (see docs/runtime-notes.md) — pinning the zone removes the *variance*,
 * not the dependency. This test exists so that if either pin is ever removed, the
 * suite goes red immediately instead of the regression surfacing months later as
 * a mysterious off-by-an-hour request expiry on one deployment.
 */
class RuntimeTimezoneTest : StringSpec({
    "the JVM default zone is pinned to UTC" {
        TimeZone.getDefault().id shouldBe "UTC"
    }
})
