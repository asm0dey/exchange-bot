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

## Java 25 toolchain + BellSoft hardened distroless runtime + build caching

Three related changes made together: `jvmToolchain` moved from 21 to 25 (with
a pinned vendor), the production image moved from `eclipse-temurin:21-jre` to
BellSoft's hardened distroless JRE, and the `Dockerfile`'s build stage picked
up BuildKit cache mounts. Recorded together because the second forced real
changes driven by the first and third being in place.

### Versions used, and how each was confirmed to exist (not assumed)

- **`org.gradle.toolchains.foojay-resolver-convention` `1.0.0`** —
  `settings.gradle.kts`. Confirmed current via the Gradle Plugin Portal's own
  Maven metadata, not a scraped page:
  `curl https://plugins.gradle.org/m2/org/gradle/toolchains/foojay-resolver-convention/org.gradle.toolchains.foojay-resolver-convention.gradle.plugin/maven-metadata.xml`
  — `<release>1.0.0</release>`, dated 2025-05-19, newest of the eleven listed
  versions (`0.1` through `1.0.0`).
- **`bellsoft/hardened-liberica-runtime-container:jre-25-cds-distroless-glibc`**
  — confirmed to exist via the Docker Hub *registry* API directly (not an AI
  summary of a web page):
  `curl https://hub.docker.com/v2/repositories/bellsoft/hardened-liberica-runtime-container/tags?name=jre-25-cds-distroless-glibc`
  returned one active tag, pushed 2026-08-29, with both `linux/amd64` and
  `linux/arm64` manifests. Then actually pulled it (`docker pull`) and
  inspected it (below) rather than trusting the tag existing to mean it's
  usable.
- **`gradle:9.6.1-jdk25`** (build stage) — confirmed via the same Docker Hub
  registry API against `library/gradle`, matching this project's exact
  `gradle-wrapper.properties` version (`9.6.1`) with a JDK 25 variant, then
  pulled and inspected directly (`docker run ... java -version` /
  `gradle --version`): Gradle 9.6.1, Temurin JDK 25.0.3.

### The Java 25 gate — toolchain resolution and vendor

The build host has JDK 21 and JDK 26 under `/usr/lib/jvm/`, and
`settings.gradle.kts` previously configured no toolchain resolver, so Gradle
could not provision 25 on its own. Adding the foojay resolver convention
plugin fixed that — verified by a `--no-daemon clean test` run that showed
Gradle's toolchain-detection log lines resolving `foojay-resolver-convention`
and, on a *first* pass with an un-vendored `jvmToolchain(25)`, actually
downloading and using `azul-zulu-25.32.21-25.0.2-glibc` into
`~/.gradle/gradle-jdks/` — proof the resolver mechanism itself works, not
just that a locally-installed 25 happened to satisfy the spec.

That first pass is also why the toolchain got a vendor pin. Whatever JDK 25
foojay hands back by default is unspecified — here it was Azul Zulu — which
would mean the JDK compiling the code is a different vendor from the Liberica
JRE running it in the container. `build.gradle.kts` now pins:

```kotlin
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.BELLSOFT)
    }
}
```

Verified this actually took effect, not just declared: re-ran
`--no-daemon clean test --rerun --info` and grepped the log for the real
toolchain paths used —

- `compileKotlin`: `[KOTLIN] Kotlin compilation 'jdkHome' argument:
  /home/finkel/.sdkman/candidates/java/25.0.4.fx-nik`
- the forked `Gradle Test Executor 1` process command line:
  `/home/finkel/.sdkman/candidates/java/25.0.4.fx-nik/bin/java ... -Duser.timezone=UTC ...`

— both resolving to a locally-installed **BellSoft Liberica JDK 25 (25.0.4+10-LTS)**,
not the Temurin/Zulu builds also present on the machine. Separately, inside an
isolated Docker build (`gradle:9.6.1-jdk25`, whose own bundled JDK is Temurin
25, with no BellSoft JDK pre-installed and no host SDKMAN cache available),
`./gradlew --no-daemon installDist` still succeeded — confirming the vendor
pin plus foojay resolver combination works from a clean environment, not only
because this particular dev machine happens to have a matching JDK already
installed.

Kotlin `2.4.10` and KSP `2.3.10` both compiled and ran clean against target 25
— no compiler/plugin version changes were made or needed. Full suite:
**158/158 tests pass** (`tests=158 skipped=0 failures=0 errors=0`, tallied
from the JUnit XML, both on the host run and reproduced inside the Docker
build stage).

### What the distroless switch forced to change

- **The `installDist` shell launcher (`bin/exchange-bot`) cannot run.**
  Distroless ships no shell. `ENTRYPOINT` now invokes `java` directly in exec
  form against an explicit classpath over the distribution's `lib/` directory:
  `ENTRYPOINT ["java", "-Duser.timezone=UTC", "-cp", "/app/lib/*", "fxbot.MainKt"]`.
  The `/app/lib/*` wildcard is expanded by the JVM's own classpath-wildcard
  handling (a `java` launcher feature, not shell globbing), so it works with
  no shell present — confirmed by actually running the container (below).
- **`applicationDefaultJvmArgs` (`-Duser.timezone=UTC`) lives inside the
  bypassed launcher script**, so it is silently dropped by invoking `java`
  directly. Carried explicitly in `ENTRYPOINT` instead, with a comment
  in `Dockerfile` explaining why it's duplicated so a future cleanup pass
  doesn't remove it thinking it's redundant with `ENV TZ=UTC`. Both are kept —
  belt and braces, as the original comment already framed it.
- **The runtime user changed identity.** The old `eclipse-temurin:21-jre`
  stage created its own user (`useradd --uid 10001 exchange-bot`). The
  hardened distroless image ships its own fixed nonroot user instead — no
  `useradd`/`chown` binaries exist in it to create one. Established the
  actual identity by `docker cp`-ing `/etc/passwd` and `/etc/group` out of
  the image (it has no shell/`cat` to read them in place):
  `appuser:x:10001:10001::/home/appuser:/bin/sh`. Coincidentally the same
  numeric uid as the old Dockerfile's custom user, but that's not assumed —
  it's the number actually shipped by this image, also visible in
  `docker history --no-trunc` for the base image layer
  (`addgroup -g 10001 appuser; adduser -u 10001 -G appuser ...`).
  `Dockerfile` sets `USER 10001:10001` explicitly (numeric, not the name
  `appuser`) — required for Kubernetes `runAsNonRoot`, which rejects a
  non-numeric `USER` outright since it can't otherwise prove the image isn't
  root, and safer generally since a name only resolves if whatever reads the
  image config can look it up in `/etc/passwd`. Confirmed the *running*
  container actually uses this uid, not just the image metadata: `docker top`
  on a live container showed `UID 10001` for the `java` process, and
  `/proc/<pid>/cmdline` read from the host (containers share the host PID
  namespace's `/proc`) confirmed the same.
- **The data directory has to be pre-created and pre-owned**, since the
  distroless stage can't `mkdir`/`chown` it itself. The build stage (which
  does have a shell) creates an empty directory owned by `10001:10001` and
  the final stage copies it in with `COPY --from=build --chown=10001:10001`.
  This still lines up with `compose.yaml`'s named volume: Docker seeds a
  named volume's ownership from the image path it's mounted over on first
  use, and that path (`/app/data`) is exactly the copied-in, correctly-owned
  directory.
- **CDS variant, deliberately.** Chose `jre-25-cds-distroless-glibc` over the
  non-CDS tag: the bot's process restarts on every deploy and dependency
  bump, so JVM startup time is a recurring cost, not a one-off. The `-cds`
  image ships a prebuilt class-data-sharing archive for its own JRE classes;
  this is used automatically (CDS auto-enables from the default archive on
  JDK 19+) with no extra flags required. Chose `glibc` over `musl`/Alpine
  because that's the C library this project's dependencies (Tink, H2 native
  paths where applicable) have actually been exercised against — no
  Alpine-specific testing exists for this codebase, and switching libc
  families is not a change worth making inside the same PR as the runtime
  swap.

### Build-layer caching

`Dockerfile`'s build stage mounts a BuildKit cache at
`/home/gradle/.gradle`, which is where this actually resolves: the
`gradle:9.6.1-jdk25` image sets no `GRADLE_USER_HOME`, so Gradle falls back to
`$HOME/.gradle`; the image's default user is `root` (`HOME=/root`); and
`/root/.gradle` is a symlink baked into the image pointing at
`/home/gradle/.gradle` — confirmed with
`docker run gradle:9.6.1-jdk25 sh -c 'readlink /root/.gradle'` before writing
the mount target, rather than guessing.

Layer sequence: `gradlew`, `gradle/`, `settings.gradle.kts`, and
`build.gradle.kts` are copied and a cache-mounted `./gradlew dependencies`
warms the dependency cache, *then* `src/` is copied and a second
cache-mounted `./gradlew installDist` compiles. A source-only change now only
invalidates the `COPY src` layer onward; the dependency-resolution layer
above it stays cached. Confirmed by two builds back-to-back: the first (cold)
took the full ~50s+30s for dependency resolution and compile; a second build
with no source changes showed every step through `installDist` as `CACHED` in
BuildKit's plain-progress output.

Confirmed the cache mount contributes nothing to the final image: BuildKit
cache mounts are backed by BuildKit's own cache store, not a union-fs layer,
so a `RUN --mount=type=cache` step commits no layer diff by construction —
and separately, the *build* stage that uses the mount is discarded entirely
by the multi-stage build; only `lib/` and the pre-owned empty `data/`
directory are copied into the final image. `docker history --no-trunc` on the
built image shows exactly that: `COPY .../lib ./lib` at 29.5MB (the app jars
and their dependencies) and every other application-added layer at `0B`
(`WORKDIR`, `COPY data-seed`, `USER`, `VOLUME`, both `ENV`s, `ENTRYPOINT`) —
no Gradle-cache-sized layer anywhere. Final image: 236MB.

### End-to-end verification (Docker available; all of this was actually run)

1. **Build succeeds, twice** — once cold (`--no-cache`), once incremental,
   confirming both correctness and that the cache-mount/layer-sequencing claims
   above hold in practice (the incremental build hit `CACHED` through
   dependency resolution and `installDist`).
2. **Fails correctly on missing config, not on infrastructure**: `docker run
   --rm exchange-bot:java25` with no environment exits 1 with
   `java.lang.IllegalStateException: BOT_TOKEN environment variable is
   required` at `Config.kt:26` — the same config-validation error the app has
   always raised, reached via a full Kotlin coroutine stack trace. No
   `ClassNotFoundException` (the `-cp /app/lib/*` wildcard classpath
   resolved), no `exec format error` / missing-interpreter failure (no shell
   was needed), no permission error (this failure happens before any
   filesystem access).
3. **Full startup path, with real generated secrets**: ran the container
   again with `BOT_TOKEN`/`DB_FILE_KEY`/`DB_USER_PW` set to placeholder
   values and `DATA_KEYSET`/`INDEX_KEYSET` from an actual `./gradlew keygen`
   run (not fabricated). Logs showed: Hikari connecting to
   `jdbc:h2:file:/app/data/exchange`, Flyway creating
   `flyway_schema_history` and applying both migrations, db-scheduler
   starting its recurring tasks, rate/housekeeping sweeps running, and
   finally `exchange-bot: listening` followed by a Telegram API `401
   Unauthorized` on the dummy token — i.e. it got all the way through
   filesystem I/O, DB migration, and scheduler startup, and only failed on
   the one thing a dummy token is expected to fail on. This is the positive
   confirmation that `/app/data` is genuinely writable by the running user,
   not just that the negative (missing-token) case failed early enough to
   not exercise it.
4. **Timezone pin reaches the real running JVM, not just the Dockerfile**:
   with the container above still running, `docker inspect -f
   '{{.State.Pid}}'` gave the host-visible PID, and reading
   `/proc/<pid>/cmdline` directly from the host (containers share the host
   PID namespace) showed the exact live argv:
   `java -Duser.timezone=UTC -cp /app/lib/* fxbot.MainKt`. This is stronger
   than checking `docker inspect`'s `Config.Entrypoint` (which only proves
   the image *declares* the flag) — it proves the flag was actually present
   on the command line of the process the kernel is running.
5. **Running uid**: `docker top` on the live container showed `UID 10001`
   for the `java` process, cross-checked against `/etc/passwd` pulled from
   the image (§ above) and the base image's own `docker history` build
   script (`adduser -u 10001 -G appuser ...`) — three independent
   confirmations of the same number.

### Unverified / out of scope

- **Kubernetes `runAsNonRoot` behavior** was not tested against an actual
  cluster — only reasoned about from the numeric-`USER` requirement. The
  Docker-level verification (uid 10001 confirmed three ways) is the extent of
  what was checked here.
- **`linux/arm64`** was not built or run — only confirmed to exist as a
  manifest for both the BellSoft and Gradle images via the registry API. All
  builds and runs above were `linux/amd64`.
- **Musl/Alpine libc** was deliberately not evaluated as an alternative to
  `glibc` — see the CDS-variant note above.
- **CI runner availability of Docker/BuildKit** was not checked — this
  project's CI configuration is unchanged by this work; only the local build
  and run were verified.
