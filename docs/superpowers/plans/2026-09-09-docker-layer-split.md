# Docker Dependency/App Layer Split Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Copy the app's own jar into its own Docker image layer, separate from
the 26.5 MB of third-party dependency jars, so a source-only change re-pushes
and re-pulls ~0.5 MB instead of 27 MB.

**Architecture:** `installDist` writes the project jar and its 47 dependency
jars side by side into one `lib/` directory, and the runtime stage copies that
whole directory in a single `COPY`. The build stage moves the project jar out
of `lib/` to a fixed path (`/stage-app.jar`); the runtime stage then copies the
stable dependency directory first and the volatile app jar last, and the
`ENTRYPOINT` classpath names both. No Gradle change, no new dependency.

**Tech Stack:** Docker/BuildKit multi-stage build, Gradle Application plugin
(`installDist`), Kotlin + Kotest (for the regression guard).

**Spec:** none — this plan is its own spec. The "Problem" section below is the
requirement; it was confirmed against the working tree, not assumed.

## Problem (measured, not estimated)

On this checkout, after `./gradlew installDist`:

```
$ du -sh build/install/exchange-bot/lib/
27M     build/install/exchange-bot/lib/
$ du -sh build/install/exchange-bot/lib/exchange-bot-0.jar
508K    build/install/exchange-bot/lib/exchange-bot-0.jar
$ ls build/install/exchange-bot/lib/ | wc -l
48
```

`Dockerfile`'s runtime stage has one `COPY --from=build
/src/build/install/exchange-bot/lib ./lib`. A Docker `COPY` layer's cache key
is the content of what it copies, so changing one Kotlin file changes that
508 KB jar, which invalidates the whole 27 MB layer. Every release push to GHCR
ships 27 MB; every deploy pulls 27 MB. The dependency half of that is
byte-identical between builds unless `build.gradle.kts` or
`gradle/libs.versions.toml` changed.

The build-stage Gradle cache is already handled (BuildKit `--mount=type=cache`
plus the build-scripts-before-`src` COPY ordering). This plan fixes the
*image* half, which that ordering does not touch.

## Global Constraints

Copied from the existing `Dockerfile` and its comments. Every task's
requirements implicitly include these — a change that breaks any of them is a
regression even if the layer split works.

- The runtime stage is **distroless: there is no shell**. `ENTRYPOINT` must
  stay in exec form (JSON array), and no `RUN`/shell command may be added to
  the runtime stage.
- `-Duser.timezone=UTC` must remain in the `ENTRYPOINT` argv. It lives in
  `applicationDefaultJvmArgs`, which only reaches the `installDist` bash
  launcher — and that launcher is bypassed here. `ENV TZ=UTC` is belt-and-braces
  alongside it, **not** a replacement.
- `USER 10001:10001` stays numeric (Kubernetes `runAsNonRoot` rejects a
  non-numeric `USER`).
- `./lib` and the app jar stay **root-owned** (the process never modifies its
  own jars). Only `./data` is `--chown=10001:10001`.
- `VOLUME ["/app/data"]`, `ENV DB_PATH=/app/data/exchange`, `WORKDIR /app` and
  the base image tag `bellsoft/hardened-liberica-runtime-container:jre-25.0.4_9-cds-distroless-glibc`
  are unchanged.
- The `--mount=type=cache,target=/home/gradle/.gradle` BuildKit cache mounts in
  the build stage are unchanged.
- Main class is `fxbot.MainKt`.

## Preconditions

The working tree at the time this plan was written has uncommitted in-progress
changes that **do not compile** (`Main.kt:82` `No value passed for parameter
'asks'`, plus argument-type mismatches across several test files). Start this
work from a tree where `./gradlew build` passes — stash, commit, or branch off
`main` first. Task 2 builds a real image and will fail on a broken tree for
reasons unrelated to this plan.

## File Structure

- `Dockerfile` — the change itself: one added `mv` in the build stage, one
  `COPY` split into two in the runtime stage, one `ENTRYPOINT` classpath edit.
- `src/test/kotlin/fxbot/DockerfileLayeringTest.kt` — **new.** Regression guard
  that reads `Dockerfile` as text and asserts the split is still there. Same
  role as the existing `RuntimeTimezoneTest`: this is a property nothing else
  in the suite would notice being silently reverted by a future cleanup pass.
- `docs/runtime-notes.md` — the verification transcript, per this repo's
  convention of recording *how* each infrastructure claim was actually checked.

---

### Task 1: Split the layers, guarded by a test

**Files:**
- Create: `src/test/kotlin/fxbot/DockerfileLayeringTest.kt`
- Modify: `Dockerfile` (build-stage `installDist` RUN; runtime-stage `COPY`
  block; `ENTRYPOINT` line)

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: the image path `/app/app.jar` (the project's own jar, at a fixed
  version-independent name) and `/app/lib/` (dependencies only). Task 2's
  verification commands rely on exactly those two paths and on `/stage-app.jar`
  existing in the build stage.

**Why a test that greps a Dockerfile:** it is string matching, and that is a
real weakness — a reformat can break it. It is worth it anyway because the
failure mode is *silent*: merging the two `COPY`s back into one produces an
image that runs perfectly and quietly reintroduces the 27 MB push. Nothing else
would catch that. The assertions below deliberately match on meaningful tokens
via regex rather than exact whole lines, so ordinary comment edits do not break
them. This mirrors `RuntimeTimezoneTest`, which exists for the same reason.

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/fxbot/DockerfileLayeringTest.kt`:

```kotlin
package fxbot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.string.shouldContain
import java.io.File

/**
 * Guards the dependency/app layer split in `Dockerfile` — see
 * docs/runtime-notes.md. `installDist` writes the project's own jar into the
 * same `lib/` directory as its ~47 dependency jars; copying that directory as
 * one image layer makes every source-only change invalidate all 27 MB of it.
 *
 * The split's failure mode is silent: collapsing the two COPYs back into one
 * still produces a working image, and only the registry push/pull size
 * regresses. Nothing else in the suite would notice, so this asserts on the
 * Dockerfile's text directly. Matching is by token, not whole line, so
 * rewording the surrounding comments does not break it.
 *
 * Gradle's Test task runs with the project directory as its working directory,
 * so the relative path below resolves to the repo root.
 */
private val DOCKERFILE: String = File("Dockerfile").readText()

/** Only the runtime stage — the build stage mentions the same paths for other reasons. */
private val RUNTIME_STAGE: String =
    DOCKERFILE.substringAfter("FROM bellsoft/hardened-liberica-runtime-container")

private fun positionInRuntimeStage(pattern: String): Int =
    Regex(pattern).find(RUNTIME_STAGE)?.range?.first ?: -1

class DockerfileLayeringTest : StringSpec({

    "the build stage moves the app jar out of the dependency directory" {
        DOCKERFILE shouldContain
            Regex("""mv\s+\S*/install/exchange-bot/lib/exchange-bot-\*\.jar\s+/stage-app\.jar""")
    }

    "the dependency layer is copied before the app layer" {
        val depsLayer = positionInRuntimeStage("""COPY[^\n]*/install/exchange-bot/lib[^\n]*""")
        val appLayer = positionInRuntimeStage("""COPY[^\n]*/stage-app\.jar[^\n]*""")

        // Both present at all...
        depsLayer shouldBeGreaterThan -1
        appLayer shouldBeGreaterThan -1
        // ...and the stable one first: Docker invalidates a layer and every
        // layer after it, so copying the volatile app jar first would put the
        // 26.5 MB dependency layer downstream of it and defeat the whole split.
        appLayer shouldBeGreaterThan depsLayer
    }

    "the entrypoint classpath names both layers" {
        val entrypoint = DOCKERFILE.lineSequence().first { it.startsWith("ENTRYPOINT") }
        entrypoint shouldContain "/app/app.jar"
        entrypoint shouldContain "/app/lib/*"
        // Still bypassing the bash launcher, so the zone pin has to be carried here.
        entrypoint shouldContain "-Duser.timezone=UTC"
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests 'fxbot.DockerfileLayeringTest'`

Expected: FAIL. Three failures — the `mv` regex finds nothing, the app-layer
`COPY` position is `-1`, and the `ENTRYPOINT` does not contain `/app/app.jar`
(it currently reads `"-cp", "/app/lib/*"`).

- [ ] **Step 3: Move the app jar out of `lib/` in the build stage**

In `Dockerfile`, replace this block:

```dockerfile
COPY src ./src
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon installDist
```

with:

```dockerfile
COPY src ./src
# installDist writes the project's own jar into the same lib/ directory as its
# ~47 dependency jars. Moving it out here is what lets the runtime stage below
# copy the two halves as separate image layers — see the COPY pair there.
#
# The glob resolves to exactly one file: the project's own jar, whose name
# carries $VERSION (exchange-bot-0.jar locally, exchange-bot-<tag>.jar in the
# release workflow), which is why this can't be a literal name. A destination
# that is a plain file means `mv` fails loudly if the glob ever matches zero or
# several jars, so a rename upstream stops the build instead of silently
# shipping an image with no application code on its classpath.
RUN --mount=type=cache,target=/home/gradle/.gradle \
    ./gradlew --no-daemon installDist \
 && mv /src/build/install/exchange-bot/lib/exchange-bot-*.jar /stage-app.jar
```

- [ ] **Step 4: Split the runtime-stage COPY and update the classpath**

In `Dockerfile`, replace this block:

```dockerfile
# lib/ is the app's code: root-owned, read-only to the running user (least
# privilege — the process never needs to modify its own jars).
COPY --from=build /src/build/install/exchange-bot/lib ./lib
# data/ is the app's mutable state: owned by appuser so it can write the H2
# database there, and matching the named volume compose.yaml mounts at this
# path (Docker seeds a named volume's ownership from the image on first use).
COPY --from=build --chown=10001:10001 /data-seed ./data
```

with:

```dockerfile
# Two layers for the classpath, deliberately, and in this order.
#
# lib/ is ~26.5 MB of third-party jars that change only when build.gradle.kts or
# gradle/libs.versions.toml does. app.jar is ~0.5 MB that changes on every
# commit. A COPY layer's cache key is the content of what it copies, and Docker
# invalidates a layer plus everything after it — so with both in one directory,
# editing one Kotlin file re-pushed and re-pulled all 27 MB. Copying the stable
# half first cuts that to ~0.5 MB per source-only change.
#
# Collapsing these back into one COPY still produces a working image and only
# regresses the push/pull size, which is why the regression is silent and why
# src/test/kotlin/fxbot/DockerfileLayeringTest.kt asserts the split is here.
#
# Both are root-owned and read-only to the running user (least privilege — the
# process never needs to modify its own jars).
COPY --from=build /src/build/install/exchange-bot/lib ./lib
# data/ is the app's mutable state: owned by appuser so it can write the H2
# database there, and matching the named volume compose.yaml mounts at this
# path (Docker seeds a named volume's ownership from the image on first use).
# Copied before app.jar because it never changes; anything volatile goes last.
COPY --from=build --chown=10001:10001 /data-seed ./data
COPY --from=build /stage-app.jar ./app.jar
```

Then replace the `ENTRYPOINT` line:

```dockerfile
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-cp", "/app/lib/*", "fxbot.MainKt"]
```

with:

```dockerfile
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-cp", "/app/app.jar:/app/lib/*", "fxbot.MainKt"]
```

Note the classpath lists app.jar **first** while the COPYs put lib **first**.
That is not an inconsistency: filesystem layer order and classpath search order
are unrelated. Layer order is chosen for cache stability (stable first);
classpath order is chosen so application classes win over any dependency that
ships a colliding name. `/app/lib/*` is still expanded by the JVM's own
classpath-wildcard handling, not by a shell — distroless has none — and that
expansion applies per classpath element, so mixing a plain jar path and a
wildcard directory in one `-cp` is fine.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew test --tests 'fxbot.DockerfileLayeringTest'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Run the whole suite**

Run: `./gradlew build`
Expected: PASS. No production code changed, so nothing else should move; this
catches the case where the new test file fails to compile against the project's
Kotest version.

- [ ] **Step 7: Commit**

```bash
git add Dockerfile src/test/kotlin/fxbot/DockerfileLayeringTest.kt
git commit -m "perf(docker): copy app jar and dependencies as separate layers

installDist puts the 508K project jar in the same lib/ directory as 26.5M of
dependency jars, so one COPY made every source-only change invalidate all 27M.
Move the app jar to /app/app.jar in its own COPY, after the dependency COPY.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Prove the split actually works, and record how

**Files:**
- Modify: `docs/runtime-notes.md` (append a subsection under
  `## Java 25 toolchain + BellSoft hardened distroless runtime + build caching`,
  which already hosts the `Dockerfile` verification transcripts)

**Interfaces:**
- Consumes: the `/app/app.jar` + `/app/lib/` image layout and `/stage-app.jar`
  build-stage path from Task 1.
- Produces: nothing other tasks depend on.

**Why this is a separate task:** Task 1's test proves the *Dockerfile text* says
the right thing. It does not prove Docker actually reuses the dependency layer,
and it does not prove the new classpath still starts the JVM. Those need a real
`docker build`, which no unit test can do. This repo's convention
(`docs/runtime-notes.md`, first paragraph) is to record how each claim "was
actually verified rather than assumed" — so the verification and its transcript
are one deliverable.

Requires Docker with BuildKit. Modern Docker enables it by default; if these
commands error on `--mount=type=cache`, prefix them with `DOCKER_BUILDKIT=1`.

- [ ] **Step 1: Build the image once**

```bash
docker build -t exchange-bot:layer-a .
```

Expected: succeeds. Record the wall-clock time.

- [ ] **Step 2: Make a source-only change and rebuild**

```bash
printf '\n// layer-cache probe — reverted below\n' >> src/main/kotlin/fxbot/Money.kt
docker build -t exchange-bot:layer-b .
git checkout src/main/kotlin/fxbot/Money.kt
```

Expected: the second build succeeds. `git checkout` restores the probe file —
do not commit it.

- [ ] **Step 3: Confirm only the last layer differs**

```bash
diff \
  <(docker inspect exchange-bot:layer-a --format '{{range .RootFS.Layers}}{{println .}}{{end}}') \
  <(docker inspect exchange-bot:layer-b --format '{{range .RootFS.Layers}}{{println .}}{{end}}')
```

Expected: exactly one differing line, and it is the **last** line of each list —
the app.jar layer. Every earlier digest (base image layers, `./lib`, `./data`)
is identical. If more than one line differs, the split is not working: check
that the app-jar `COPY` really is the last `COPY` in the runtime stage.

- [ ] **Step 4: Confirm the two layers are the sizes claimed**

```bash
docker history exchange-bot:layer-b --no-trunc --format '{{.Size}}\t{{.CreatedBy}}' | head -8
```

Expected: a `COPY ... lib ./lib` entry around 26–27 MB and a
`COPY ... /stage-app.jar ./app.jar` entry around 500 KB. These are the numbers
the runtime-notes entry will quote — copy the actual output, do not carry the
figures from this plan over unchecked.

- [ ] **Step 5: Confirm the container still starts on the new classpath**

```bash
docker run --rm exchange-bot:layer-b
```

Expected: exits non-zero, with `BOT_TOKEN environment variable is required` on
stderr (from `Config.kt`'s `required()`). That message is the proof that
matters: reaching it means the JVM launched with no shell, resolved
`fxbot.MainKt` from `/app/app.jar`, loaded the Kotlin stdlib and everything
else `Main` touches from `/app/lib/*`, and got as far as application code. A
`ClassNotFoundException` or `NoClassDefFoundError` instead means the classpath
split is wrong. (Same idiom already used in `docs/runtime-notes.md` to prove
`applicationDefaultJvmArgs` reached the `installDist` launcher.)

- [ ] **Step 6: Confirm the timezone pin survived**

```bash
docker inspect exchange-bot:layer-b --format '{{json .Config.Entrypoint}}'
```

Expected: `["java","-Duser.timezone=UTC","-cp","/app/app.jar:/app/lib/*","fxbot.MainKt"]`.
The pin is easy to lose while editing this line, and losing it reintroduces the
off-by-an-hour expiry bug that `RuntimeTimezoneTest` and the runtime-notes
section above it exist to prevent.

- [ ] **Step 7: Record the verification**

Append to `docs/runtime-notes.md`, as a new `###` subsection at the end of the
`## Java 25 toolchain + BellSoft hardened distroless runtime + build caching`
section (i.e. immediately before the `## Bot-token validation at startup`
heading):

```markdown
### The app jar and its dependencies are separate image layers

**The gap.** `installDist` writes the project's own jar into the same `lib/`
directory as its dependency jars, and the runtime stage copied that whole
directory with one `COPY`. Measured on this checkout: `lib/` is 27 MB across 48
jars, of which the project's own `exchange-bot-<version>.jar` is 508 KB. A
`COPY` layer's cache key is the content of what it copies, so editing one
Kotlin file invalidated the entire 27 MB layer — pushed to GHCR on every
release and pulled on every deploy, to ship half a megabyte of changed code.

The build-stage Gradle caching already in place (BuildKit `--mount=type=cache`
plus copying the build scripts before `src`) does not help here: it keeps the
*build* fast, while this is about the *image's* layer boundaries.

**What changed.** The build stage moves the project jar out of `lib/` to
`/stage-app.jar` right after `installDist` (glob, because the file name carries
`$VERSION`; a plain-file destination makes `mv` fail loudly rather than
silently produce a classpath with no application code). The runtime stage then
copies `./lib` and `./data` first and `./app.jar` last, and `ENTRYPOINT` names
both: `-cp /app/app.jar:/app/lib/*`.

Classpath order (app first) is intentionally the reverse of `COPY` order (deps
first) — the two are unrelated. Layer order is chosen for cache stability;
classpath order so application classes win any name collision.

**How it was verified.**

1. **Only the app layer changes on a source-only edit.** Built the image,
   appended a comment line to `src/main/kotlin/fxbot/Money.kt`, rebuilt, and
   diffed `docker inspect --format '{{range .RootFS.Layers}}...'` between the
   two tags. Exactly one digest differed, and it was the last one. Every base
   image layer, `./lib` and `./data` were byte-identical.
   <!-- REPLACE with the actual diff output from Step 3 -->
2. **The layers are the sizes claimed.** `docker history --no-trunc` showed the
   `./lib` COPY at <!-- REPLACE: actual size from Step 4 --> and the `./app.jar`
   COPY at <!-- REPLACE: actual size from Step 4 -->.
3. **The container still starts on the split classpath.** `docker run --rm`
   with no environment reached `Config.kt`'s own validation and failed with
   `BOT_TOKEN environment variable is required` — not `ClassNotFoundException`.
   That proves the JVM launched with no shell present, resolved `fxbot.MainKt`
   from `/app/app.jar`, and loaded the Kotlin stdlib and everything else `Main`
   touches from `/app/lib/*`. The JVM's own classpath-wildcard expansion
   applies per element, so mixing a jar path and a wildcard directory in one
   `-cp` works.
4. **The timezone pin survived the ENTRYPOINT edit.**
   `docker inspect --format '{{json .Config.Entrypoint}}'` returned
   `["java","-Duser.timezone=UTC","-cp","/app/app.jar:/app/lib/*","fxbot.MainKt"]`.

**Guard.** `src/test/kotlin/fxbot/DockerfileLayeringTest.kt` asserts the `mv`
exists, that the dependency `COPY` precedes the app `COPY` in the runtime
stage, and that `ENTRYPOINT` names both paths and keeps the zone pin. It reads
the `Dockerfile` as text, which is admittedly brittle — justified because the
regression is silent: merging the two `COPY`s back into one yields an image
that runs perfectly and only regresses registry traffic, which no behavioural
test would catch.
```

Replace every `<!-- REPLACE ... -->` marker with the real output captured in
Steps 3–4. Leaving a marker in place is a plan failure, not a formatting nit.

- [ ] **Step 8: Clean up the local images**

```bash
docker image rm exchange-bot:layer-a exchange-bot:layer-b
git status --short   # expect only docs/runtime-notes.md modified
```

Expected: `Money.kt` is **not** listed (Step 2 reverted it). If it is, run
`git checkout src/main/kotlin/fxbot/Money.kt` before committing.

- [ ] **Step 9: Commit**

```bash
git add docs/runtime-notes.md
git commit -m "docs: record how the docker layer split was verified

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Out of scope, on purpose

- **Splitting dependencies further** (e.g. a rarely-changing "stdlib + ktor"
  layer under a more volatile one). With one 26.5 MB dependency set that turns
  over only on a Renovate bump, the extra `Dockerfile` machinery buys nothing
  measurable. Revisit if dependency churn ever becomes the dominant push cost.
- **A Gradle task that emits the split** (a `Sync` of `runtimeClasspath` into
  `build/layers/deps` and the jar into `build/layers/app`). Two lines of `mv`
  in the build stage do the same job with nothing new to maintain, and keep the
  layout decision next to the layers it creates.
- **`shadowJar` for the image.** The fat jar is the GitHub Release artifact
  (`build/release/`), and by construction it cannot be layer-split — every
  build rewrites one ~27 MB file. The image deliberately uses `installDist`
  instead; that stays.
- **Gradle module layers** (`:domain` / `:persistence` / `:telegram`). Different
  change, different plan.
