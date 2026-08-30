# Logging: tinylog migration notes

Records what was shipped when `slf4j-simple` was replaced with tinylog, why the
suppression rules are what they are, and how each claim below was actually checked
rather than assumed. Written because the previous mitigation (silencing two SLF4J
logger names) looked complete and was not — see "What was wrong with the old
mitigation" below.

## Coordinates and versions

- `org.tinylog:tinylog-impl:2.7.0` — the tinylog engine (writers, configuration).
- `org.tinylog:slf4j-tinylog:2.7.0` — the SLF4J-to-tinylog binding (registers
  `org.slf4j.spi.SLF4JServiceProvider` via `META-INF/services`, so it's the
  slf4j 2.x SPI mechanism, not the legacy `StaticLoggerBinder` one).
- `org.tinylog:tinylog-api:2.7.0` — pulled in transitively by both of the above;
  not declared directly since nothing in this codebase calls tinylog's own API.

2.7.0 is the newest **stable** release on Maven Central as of this change (checked
via the Maven Central search API against `org.tinylog:tinylog-impl`,
`:tinylog-api`, `:slf4j-tinylog`). `2.8.0-M1` exists but is a milestone
(pre-release), so it was not used. `slf4j-tinylog:2.7.0`'s POM declares a compile
dependency on `slf4j-api:2.0.11`; Gradle's default highest-version-wins resolution
still picks `slf4j-api:2.0.18`, which the rest of the dependency graph already
pulls in (`kotlinx-coroutines-slf4j`, `telegram-bot`, etc.) — no conflict, no
forced downgrade.

## What was wrong with the old mitigation

`simplelogger.properties` silenced exactly two SLF4J **logger names**:
`eu.vendeli.core.interceptors.InvokeInterceptor` and `eu.vendeli.tgbot.TelegramBot`.
That worked under slf4j-simple, which filters by the logger name a caller happened
to register (an arbitrary string passed to `LoggerFactory.get(...)`, often *not*
the calling class's real name — the file's own comment noted this for the
interceptor case).

tinylog does not filter by logger name at all. `level@<key>` in `tinylog.properties`
matches the **real calling class**, found by walking the JVM stack trace at the log
call site (`TinylogLoggingProvider.isLoggable` → `getLevel(callerClassName)`,
confirmed by reading `tinylog-impl-2.7.0-sources.jar`). Porting the two old logger-
name strings into `level@...` keys would compile, look plausible, and silently
suppress nothing, because neither string is a real package or class name that any
stack frame will ever report. Confirmed this empirically (below) before shipping
anything.

Worse: naming just those two logger names never covered every place the library
logs identifying data. Auditing the actual `eu.vendeli:telegram-bot-jvm:9.6.0`
sources turned up two more real leak paths the old config's own comment didn't
mention:

- `eu.vendeli.tgbot.utils.common.BotUtilsKt` (`checkIsLimited`) logs the raw
  Telegram user id at **INFO** when a configured rate limit trips
  (`"User #$telegramId has exceeded the request limit..."`). This function is
  `inline`, so at every real call site (`DefaultValidationInterceptor`,
  `DefaultSetupInterceptor`) its body — log call included — compiles directly into
  those classes' own bytecode. Confirmed by extracting the real
  `telegram-bot-jvm-9.6.0.jar` and grepping the compiled `.class` files with
  `strings` for the log message's literal text: it appears inside
  `DefaultValidationInterceptor.class` and `DefaultSetupInterceptor.class`
  themselves (with Kotlin inline-function name-mangling markers like `$iv`,
  `$i$f$checkIsLimited` around it), not in a separate `BotUtilsKt` frame. Both
  classes live in `eu.vendeli.tgbot.core.interceptors`, a package this config
  already fully suppresses, so this one turned out to already be covered — but
  only once actually checked, not by trusting the source-level `inline` keyword.
- `eu.vendeli.tgbot.implementations.DefaultSession` — a **private** class defined
  inside `DefaultSessionManager.kt` (NOT `DefaultSessionManager` itself, which is a
  different class in the same file and logs nothing) — logs chat id at WARN/ERROR
  when a tracked session's message-deletion batch fails
  (`"session($key) delete batch for chat=$chatId..."`). The session subsystem is
  "always-on" per the library's own doc comment (every `TelegramBot` gets one),
  though this bot never calls any `bot.sessions.*` API, so today this is dead code.
  Suppressed anyway, defensively, in case that ever changes without a re-audit of
  the framework.
- `eu.vendeli.tgbot.utils.common.BotUtils_jvmKt` (`loadContext`) logs the full
  command→handler routing table at INFO on every `TelegramBot` construction
  (`"Loaded context, current registry:"`). Not personal data, but it was silenced
  as a side effect of the old name-based rule (this call's logger happens to be
  registered under the name `"eu.vendeli.tgbot.TelegramBot"`, one of the two old
  suppressed strings) and reappeared as new console noise the first time the real
  test suite ran under tinylog — caught by the "check for log noise" step the task
  asked for, not by code inspection.

None of this was hypothetical: the `DefaultSession` vs. `DefaultSessionManager` mix-up
happened while writing *this* change — an early draft of `tinylog.properties` named
`level@eu.vendeli.tgbot.implementations.DefaultSessionManager = off`, read straight
from a quick pass over the source file, and it silently suppressed nothing (the class
that logs is the *other* one declared in that file). It was only caught because every
rule in the shipped config was independently checked against the compiled jar and a
runtime test — see below. That is the exact bug class this whole file exists to guard
against, reproduced while trying to fix it.

## How suppression was verified (not assumed)

Two independent checks, both against the real `9.6.0` artifacts:

1. **Compiled-bytecode check.** Downloaded `telegram-bot-jvm-9.6.0.jar` (the actual
   JVM binary artifact — not the multiplatform metadata jar `telegram-bot-9.6.0.jar`,
   which has no JVM `.class` files at all) and its sources jar. For each candidate
   leak, located the real `.kt` source, found the compiled `.class` file(s) with the
   same content (`unzip -l`, `javap -p`), and grepped the class bytes with `strings`
   for the log message's literal text to confirm which real class the call lives in
   at runtime — this is what caught the `DefaultSession`/`DefaultSessionManager`
   mistake.
2. **Runtime check.** Compiled small stand-in classes replicating each risky call
   site's real shape (object singleton + suspend fn, top-level suspend fn, private
   class with `runCatching{}.onSuccess{}.onFailure{}` and nested `inline forEach`,
   inline extension function) in the *exact* real packages, alongside an unrelated
   `fxbot`-package INFO/DEBUG probe and a fake third-party (`com.zaxxer.hikari`-
   shaped) DEBUG/INFO probe. Ran them under the shipped `tinylog.properties`:
   - every simulated vendeli leak (WARN "not handled", ERROR "Invocation error",
     ERROR "Request - ... received failure response", INFO "Loaded context,
     current registry:", WARN/ERROR "session(...) delete batch for chat=...")
     produced **no output**.
   - the `fxbot` INFO probe printed; the `fxbot` DEBUG probe did not (matches the
     shipped config: DEBUG is off by default, no package is bumped to it — see
     "Command-surface DEBUG events" below).
   - the fake third-party INFO probe printed; its DEBUG probe did not (proves the
     global INFO default still gates everything not explicitly named — the
     suppression is additive, not a blanket mute).
   - **Negative control:** re-ran the same harness with the *old* logger-name
     strings ported in verbatim as `level@` keys (`eu.vendeli.core.interceptors.
     InvokeInterceptor`, `eu.vendeli.tgbot.TelegramBot`) instead of the real
     packages — every simulated leak printed in full, including a full stack trace.
     This is the concrete demonstration that the naive port is a silent no-op.
   - **Second negative control:** re-ran with each of the four real `level@`
     package keys individually typo'd by one character — the corresponding leaks
     reappeared. Confirms the harness actually exercises the config rather than
     passing regardless of what's in it.
3. **Real test-suite check.** Ran this repo's full 153-test suite under the shipped
   config (`./gradlew test --rerun -i`) and grepped the output for every leak string
   above, plus `eu.vendeli` in general: zero matches, versus the pre-change baseline
   run (captured before touching any file) which also had zero matches for the two
   originally-known leaks but *did* need the new `BotUtils_jvmKt` suppression to stay
   at zero for the routing-table line, confirmed by re-diffing full unique-line sets
   before/after. Every framework log line present after the change (Hikari,
   db-scheduler, Flyway, Exposed schema-check INFO/WARN lines) is also present,
   unchanged, in the pre-change baseline.

Not verified: this bot's actual production Telegram polling path (a live long-poll
loop against `api.telegram.org`) was not exercised — only unit tests, which never
open a real bot session. The `eu.vendeli.tgbot.core.TgUpdateHandler` WARN/INFO lines
("Recoverable poll error: ...", "Starting long-polling listener.") are deliberately
left unsuppressed: `outcome.reason` there is always one of a handful of hand-written
labels from a local `classify()` function ("socket timeout", "rate limited (429)",
"server $statusCode") — confirmed by reading `TgUpdateHandler.kt`'s `classify`
directly — never a raw exception message, so nothing token-bearing reaches it. These
lines were already visible under the old slf4j-simple config too (neither call uses
either of the two old suppressed names), so leaving them visible is not a new
regression, and they carry real operational value this bot's own `Main.kt` catch
block doesn't have (it only sees classified-Fatal failures, not transient retries).
`eu.vendeli.tgbot.types.chain.WizardActivity` also logs a class name at DEBUG on a
path that never fires — this bot has no KSP-generated wizard step, so the abstract
class is never instantiated; left alone as genuinely dead code, not silenced.

## The no-PII rule, as shipped

Lives as a comment block at the top of `src/main/resources/tinylog.properties` (read
it there for the authoritative, current wording). Summary: never log user ids,
usernames, display names, chat ids, message text, amounts, currencies tied to a
specific chat/request, `ref_token`/`short_id`, or `chat_ref`/`user_ref`
(`Crypto.ref(...)` output) — the last two are called out explicitly because they're
pseudonymous but *stable*, so they're identifiers too, just ones that currently need
the encrypted DB to reverse. Never log an exception's `.message` or any cause's
`.message` (a `ClientRequestException` against `api.telegram.org` embeds the bot
token in its message via the request URL — see the standing comment in `Main.kt`).
Safe to log: counts, fixed outcome-category labels, a bare currency code standing
alone (judged non-identifying — one of a small fixed ISO 4217 set, not tied to a
chat or person), exception class names, and a freshly generated random id for
request-scoped correlation, *never* derived from `chat_ref`/`user_ref`/`ref_token` or
any other user value (so it can't be reversed back to who triggered it — no such id
was actually added in this change; the option is documented for whoever needs one).

## What was added

- **Startup** (`Main.kt`, INFO): one line naming whether `DB_PATH` is at its default
  or overridden — never the path value itself. No app version exists anywhere in
  this build (no `version` in `build.gradle.kts`/`gradle.properties`, no manifest
  attribute) to report; adding one was out of scope for a logging-only change, so
  this is flagged here rather than silently working around it.
- **Scheduler runs and counts** (`Tasks.kt` `Housekeeping.sweep`, INFO): counts of
  requests expired and messages pruned in one line. `RateService.refresh` (INFO):
  count of configured currency bases vs. how many the feed actually returned —
  covers both the daily scheduled refresh and the startup cache-warm call, since
  both go through the same function.
- **Rate-feed outcomes** (`RateService.status`, INFO): `fresh` / `degraded_to_cache`
  / `unavailable`, plus the pair's bare base currency code (judged non-identifying,
  see above — never the quote, never the amount, never the chat).
- **Listener failure streak and give-up** (`Main.kt`): the existing
  exception-class-chain diagnostic now goes through `logger.warn` for each retry and
  `logger.error` for the final give-up-and-exit, instead of `System.err.println`.
  The existing "never log `e.message`" discipline and its comment are unchanged.
- **Command-surface DEBUG events**: a shared `logCommand(command, outcome)` helper
  (`Commands.kt`, `internal`, reused from `LifecycleCommands.kt`, `AdminCommands.kt`,
  `Callbacks.kt`) logs a fixed command name and a fixed outcome label at DEBUG —
  e.g. `command=sell outcome=posted`, `command=cancel outcome=denied`,
  `command=done_button outcome=gone`. Outcome labels come from each command's own
  typed result (`PostResult.Posted`/`Rejected`, `ActionResult.Ok`/`Denied`/`Gone`
  via a new `ActionResult.outcomeLabel()`), never from inspecting free-form reply
  text. The three admin commands (`/pair`, `/tolerance`, `/tif`) are the one
  exception: `AdminService.setPair`/`setTolerance`/`setTif` return a single
  free-form `String` (validated by existing tests asserting on that exact return
  type), so there is no structured result to log a finer category from without
  string-matching the bot's own reply text. Their outcome is logged as `not_admin`
  or `handled` only — coarser than the other commands, deliberately, rather than
  adding string-sniffing that would silently break the moment a reply's wording
  changes. DEBUG is **not** turned on by default (no `level@fxbot = debug` in the
  shipped config): these events exist and are ready, but stay silent — like the
  rest of the app — unless an operator explicitly raises verbosity, so they add no
  noise to normal operation or to the test suite (confirmed: zero DEBUG lines in the
  full test-suite run under the shipped config).

## Test-suite log-noise check

Ran the full suite before and after this change and diffed the complete set of
unique log lines. After: 153/153 tests still pass; zero vendeli PII-risk lines;
zero DEBUG lines (command-surface logging stays silent by default, as intended);
the only *new* lines are this bot's own first-party INFO lines from
`RateService`/`Housekeeping`, fired because a few tests call `refresh()`/`status()`/
`sweep()` directly — not framework chatter, and carrying only counts and a bare
currency code. Every pre-existing framework/library line (Hikari, db-scheduler,
Flyway, Exposed) is unchanged from the pre-change baseline. The one framework line
that *did* newly appear on the first post-migration run — vendeli's routing-table
dump — is exactly the "framework chatter reappeared" regression this check exists to
catch, and is what led to adding the `eu.vendeli.tgbot.utils.common` suppression
rule above.
