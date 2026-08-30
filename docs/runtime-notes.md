# Runtime notes

Records JVM/process-level configuration that isn't logging (see
`docs/logging-notes.md` for that) and how each claim was actually verified rather
than assumed.

## JVM default time zone pinned to UTC (exchange-bot-2302)

**The gap.** The `request` table stores `created_at` / `expires_at` / `closed_at`
as `TIMESTAMP` (no zone). Every `Instant` written or read goes through
`java.sql.Timestamp`, and Exposed's `InstantColumnType` converts via
`TimeZone.currentSystemDefault()` on the way in and out — regardless of whether
the column itself carries a zone; that conversion is a property of
`InstantColumnType`, not of the schema, so changing the column type would not
have removed it. On a host whose JVM default zone observes DST, an `Instant`
landing in a repeated hour can read back an hour off, which shifts a request's
expiry. `Dockerfile` already sets `ENV TZ=UTC`, so the shipped container was
already safe. Not covered: `./gradlew run`, the `installDist` launcher used
directly, and the test suite — all of which take the machine's default zone.

**What was pinned, and where.**

- `build.gradle.kts`, `application { applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC") }`
  — covers `./gradlew run` and the `installDist` launcher.
- `build.gradle.kts`, `tasks.test { systemProperty("user.timezone", "UTC") }` —
  covers the test JVM, so the suite is deterministic regardless of the
  developer's machine zone.
- `src/test/kotlin/fxbot/RuntimeTimezoneTest.kt` — regression guard: asserts
  `TimeZone.getDefault().id == "UTC"`. It does not assert an exact Instant
  round-trip through `InstantColumnType` under a flipped default zone — that
  would fail, because the dependency on the JVM default zone is genuinely still
  there (see below). The guard exists so that if either pin above is removed,
  the suite goes red immediately instead of the regression surfacing months
  later as a mysterious off-by-an-hour expiry on one deployment.

**How each was verified — this machine runs Europe/Berlin (CEST, a
DST-observing zone), so an unpinned run is a real negative control, not a
hypothetical.**

1. `applicationDefaultJvmArgs` reaching `installDist`: ran
   `./gradlew installDist` and inspected the generated launcher. Both
   `build/install/exchange-bot/bin/exchange-bot` and the `.bat` counterpart
   contain `DEFAULT_JVM_OPTS='"-Duser.timezone=UTC"'` /
   `set DEFAULT_JVM_OPTS="-Duser.timezone=UTC"`, and the script's tail folds
   `DEFAULT_JVM_OPTS` into the argument list passed to the final
   `exec "$JAVACMD" "$@"` — not dead/unused script boilerplate. `./gradlew run`
   uses the same `applicationDefaultJvmArgs` wiring via the `run` `JavaExec`
   task (Application plugin); ran `./gradlew run --args="--help"` and confirmed
   the process starts and reaches `Main`'s own config validation
   (`BOT_TOKEN environment variable is required`), i.e. the JVM was already up
   with the pinned property by the time application code ran.

2. Test JVM property timing: the task instructions flagged a real risk —
   `user.timezone` is cached into `TimeZone.getDefault()` at JVM startup, so a
   property set on an *already-running* JVM is too late. Checked directly
   rather than assumed: added a temporary probe test that printed
   `TimeZone.getDefault().id`, ran it once with only
   `tasks.test { jvmArgs("-Duser.timezone=UTC") }` and once with only
   `tasks.test { systemProperty("user.timezone", "UTC") }`. Both printed `UTC`.
   Re-ran with `-i` and inspected the logged worker-process command line for
   the `systemProperty` case; Gradle had emitted it as a genuine
   `-Duser.timezone=UTC` launch argument to the forked `Gradle Test Executor`
   process (`Test` always forks a fresh JVM for the run rather than reusing a
   live one), so it is not a same-process `System.setProperty` applied too
   late. Kept `systemProperty(...)` as the final form since it's the more
   idiomatic Gradle API for this and was confirmed to work identically to
   `jvmArgs`.

3. Regression guard: ran the full suite (`./gradlew test --rerun`) after adding
   `RuntimeTimezoneTest.kt`; 158/158 tests pass (157 pre-existing + 1 new). XML
   test-result totals were tallied directly (`tests=158 skipped=0 failures=0
   errors=0`) rather than eyeballing console output.

**What this does not fix.** The coupling from `InstantColumnType` to the JVM
default zone is still there — it lives in Exposed, not in this codebase, and
was traced in an earlier review. Pinning the zone removes the *variance* (every
run mode now agrees on which zone that is), not the dependency itself. A test
that flips the JVM default zone at runtime and asserts an exact Instant
round-trip would fail, correctly, because that dependency is real; no such test
was written.
