import io.clroot.gradle.bun.task.BunInstallTask
import io.clroot.gradle.bun.task.BunTask
import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.shadow)
    alias(libs.plugins.bun.gradle)
    application
}

// Releases are integers: tag v1, v2, ... and the tag IS the version. CI exports
// VERSION (the tag minus its "v"), so cutting a release never edits this file.
// "0" is the local/dev value — an unreleased build shouldn't claim a release number.
version = providers.environmentVariable("VERSION").getOrElse("0")

repositories { mavenCentral() }

dependencies {
    implementation(libs.telegram.bot)
    ksp(libs.ktnip)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.db.scheduler)
    implementation(libs.h2)
    implementation(libs.hikari)
    implementation(libs.tink)
    implementation(libs.flyway.core)
    implementation(libs.tinylog.impl)
    implementation(libs.slf4j.tinylog)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.property)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.ktor.server.test.host)
}

// The mini app's built SPA lands in build/web/static and ships in the jar as the `static/`
// resources Ktor serves. Task 8 makes processResources build it first.
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("web")) }

// The mini app (web/) is a Svelte + Vite project with its own dependencies locked in
// web/bun.lock, run with Bun. Nobody installs Bun by hand, and a bot-only contributor needs
// nothing beyond the JDK: the bun plugin downloads the pinned release from GitHub into
// .gradle/bun/. That download is pinned by version over HTTPS, not by digest, the same trust
// every Maven dependency here gets (there is no gradle/verification-metadata.xml).
//
// No Node either. Every tool's bin script starts `#!/usr/bin/env node`, and `bun --bun`
// runs them under Bun's own runtime instead. That used to be impossible: vue-tsc's `.vue`
// import resolver never registered under Bun. svelte-check and vite both work, and the unit tests use bun test.
bun {
    version.set(libs.versions.bun.asProvider())
    workingDir.set(layout.projectDirectory.dir("web"))
}

val bunInstall = tasks.named<BunInstallTask>("bunInstall") {
    description = "Installs the mini app's own dependencies (web/bun.lock) with Bun."
    inputs.files("web/package.json", "web/bun.lock")
    outputs.dir(layout.projectDirectory.dir("web/node_modules"))
}

val webBuild = tasks.register<BunTask>("webBuild") {
    description = "Builds the mini app into build/web/static"
    dependsOn(bunInstall)
    args("--bun", "run", "build")
    inputs.dir("web/src")
    inputs.dir("web/e2e")
    inputs.files("web/index.html", "web/vite.config.ts", "web/tsconfig.json", "web/package.json", "web/bun.lock", "web/playwright.config.ts")
    outputs.dir(layout.buildDirectory.dir("web"))
}

val webTest = tasks.register<BunTask>("webTest") {
    description = "Runs the mini app's unit tests (bun test)"
    dependsOn(bunInstall)
    args("--bun", "run", "test")
    inputs.dir("web/src")
    outputs.upToDateWhen { false }
}

// Playwright needs a browser, so it is not part of `check`/`build` — only a JDK is needed for
// those. webE2e additionally needs Docker: it always runs the tests inside the pinned
// Microsoft Playwright container, identically here and in CI, instead of on whatever browser/
// font stack the host happens to have. Mismatched host fonts are exactly what made CI's
// screenshots differ from baselines made on a dev machine by ~4% (over the 2%
// maxDiffPixelRatio in playwright.config.ts) even though the same Chromium/Playwright
// versions were in use on both sides.
//
// The pinned image's own fonts aren't enough, though: its fallback for the generic
// "system-ui" family is WenQuanYi Zen Hei (a CJK font, pulled in for the image's broad
// Unicode coverage) — it has no real Bold face (every bold run rendered as regular weight)
// and doesn't cover ⇄ U+21C4 or ≈ U+2248, which the app renders as literal text characters
// (checked with `fc-match system-ui`/`fc-match system-ui:bold` and `fc-list
// :charset=<codepoint>` inside the image). `fonts-dejavu-core` is the smallest package that
// fixes both: fontconfig prefers it for "system-ui" once installed, it ships a real Bold
// face, and DejaVu Sans covers ⇄, ≈, and ✓ (U+2713, also used in the app). Fonts are
// installed fresh at the start of every run (needs network) rather than baked into a
// derived image, so this stays the exact upstream `mcr.microsoft.com/playwright` tag with
// no Dockerfile of our own to keep in sync with Playwright version bumps.
val webE2e = tasks.register<Exec>("webE2e") {
    description = "Runs the mini app's Playwright tests against mock data, inside the pinned Playwright container (needs Docker; not part of check/build)"
    dependsOn(bunInstall)
    outputs.upToDateWhen { false }

    // Pin the container to the exact @playwright/test version in web/package.json: the
    // browser bundled in the image must match the test-runner version resolved into
    // web/node_modules, or Playwright refuses to drive it (and even if it didn't, a version
    // drift would reopen exactly the rendering-mismatch problem this container exists to close).
    val packageJsonText = providers.fileContents(layout.projectDirectory.file("web/package.json")).asText.get()
    val playwrightVersion = Regex("\"@playwright/test\"\\s*:\\s*\"([^\"]+)\"")
        .find(packageJsonText)
        ?.groupValues?.get(1)
        ?.trimStart('^', '~')
        ?: throw GradleException("Could not find \"@playwright/test\" in web/package.json to pin the webE2e container image.")
    // "-noble" is Microsoft's Ubuntu-24.04 tag suffix for this Playwright release (see
    // https://mcr.microsoft.com/artifact/mar/playwright/about) — picked and pinned by hand
    // when bumping @playwright/test, since not every release is guaranteed to publish every
    // codename variant.
    val dockerImage = "mcr.microsoft.com/playwright:v$playwrightVersion-noble"

    doFirst {
        val dockerPresent = try {
            ProcessBuilder("docker", "--version").start().waitFor() == 0
        } catch (e: java.io.IOException) {
            false
        }
        if (!dockerPresent) {
            throw GradleException(
                "webE2e runs Playwright inside the pinned $dockerImage container, for " +
                    "pixel-identical screenshots locally and in CI, and needs Docker on PATH. " +
                    "Install Docker and retry."
            )
        }
    }

    val repoRoot = layout.projectDirectory.asFile.absolutePath
    val containerWebDir = "/repo/web"
    // The webServer command in playwright.config.ts (`vite build && vite preview`) is run by
    // the shell through a bare `vite`, so node_modules/.bin must be on PATH inside the
    // container — the image doesn't put a project's local bin dir there itself.
    val containerPath = listOf(
        "$containerWebDir/node_modules/.bin",
        "/usr/local/sbin", "/usr/local/bin", "/usr/sbin", "/usr/bin", "/sbin", "/bin",
    ).joinToString(":")

    // Installing fonts (apt-get) needs root, so the container starts as root — no --user
    // flag on `docker run` — and drops to the host UID/GID with `setpriv` only for the
    // `playwright test` invocation itself, so files written into the bind-mounted repo
    // (updated snapshots, test-results) still land host-owned, not root-owned. Docker
    // Desktop on Windows has no host UID/GID to map into a bind mount and doesn't need one,
    // so setpriv there is a no-op (reuid/regid 0, i.e. stay root).
    val (hostUid, hostGid) = if (OperatingSystem.current().isWindows) {
        "0" to "0"
    } else {
        val uid = providers.exec { commandLine("id", "-u") }.standardOutput.asText.get().trim()
        val gid = providers.exec { commandLine("id", "-g") }.standardOutput.asText.get().trim()
        uid to gid
    }

    // Runs as the container's root: installs the fonts (needs network), rebuilds the
    // fontconfig cache, then execs into `playwright test` as the host user via setpriv
    // (util-linux, present on this "noble"-based image). $1/$2 are the uid/gid and
    // "${@:3}" is whatever's left of the argv — see the `bash -c script bash "$@"` call
    // below; this keeps webE2eArgs values out of the shell-parsed script string entirely.
    val installAndRunScript = """
        set -euo pipefail
        apt-get update -qq
        apt-get install -y --no-install-recommends fonts-dejavu-core
        fc-cache -f
        exec setpriv --reuid="${'$'}1" --regid="${'$'}2" --clear-groups node node_modules/.bin/playwright test "${'$'}{@:3}"
    """.trimIndent()

    // -PwebE2eArgs=--update-snapshots=all reaches `playwright test` directly — there's no
    // package-manager script layer to disambiguate it from anymore, unlike the old
    // `bun run e2e --` path this replaces.
    val extraArgs = providers.gradleProperty("webE2eArgs").orNull?.let { listOf(it) } ?: emptyList()

    executable("docker")
    args(
        listOf(
            "run", "--rm",
            "-e", "HOME=/tmp",
            "-e", "PATH=$containerPath",
            "-v", "$repoRoot:/repo",
            "-w", containerWebDir,
            dockerImage,
            "bash", "-c", installAndRunScript,
            "bash", hostUid, hostGid,
        ) + extraArgs
    )
}

tasks.processResources { dependsOn(webBuild) }
tasks.check { dependsOn(webTest) }

kotlin {
    // Build on the same JDK vendor the container runs: the production runtime is
    // BellSoft Liberica (hardened distroless). Without this pin, the foojay resolver
    // hands back whatever vendor its discovery API lists first for "25" (in practice,
    // often Azul Zulu).
    //
    // This pin matches the `java.vendor` string ("BellSoft") only, not the specific
    // distribution. Gradle's JvmVendorSpec has no finer knob than vendor (+
    // JvmImplementation for HotSpot/J9) — there is no supported way to also require
    // the plain Liberica JDK distribution over a same-vendor variant such as
    // Liberica NIK (its GraalVM Native Image Kit build, which sets a distinguishing
    // `java.vendor.version` like `Liberica-NIK-25.0.4-1` that Gradle's toolchain
    // matching does not look at). Confirmed on a dev machine with several
    // `BELLSOFT`-vendor JDK 25 installs registered (plain Liberica, Liberica NIK,
    // Liberica DCEVM): `./gradlew compileKotlin --info` showed the resolved
    // toolchain landing on the NIK build, not plain Liberica, even though both
    // satisfy this vendor pin equally.
    //
    // So this pin does not guarantee "the same JDK the container ships" bit-for-bit;
    // it only guarantees a BellSoft-vendor JDK 25 locally, which is enough to catch a
    // different vendor's bytecode/behavior quirks in day-to-day dev builds. The
    // container image build is the authority for what actually ships: `Dockerfile`
    // pulls a specific `bellsoft/hardened-liberica-runtime-container` tag, which is
    // the plain Liberica JRE, not NIK.
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.BELLSOFT)
    }
}

application {
    mainClass.set("fxbot.MainKt")
    // Exposed's InstantColumnType round-trips every timestamp through the JVM default
    // zone, so a host on a DST-observing zone can read an instant back an hour off and
    // shift a request's expiry. Pinning the zone removes the variable rather than
    // handling it. The Dockerfile sets TZ=UTC for the container; this covers
    // `gradlew run` and the installDist launcher too.
    applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC")
}

tasks.test {
    useJUnitPlatform()
    // Same zone pin as the application block, for the test JVM, so the suite is
    // deterministic regardless of the developer's machine zone. user.timezone is read
    // once at JVM startup (TimeZone.getDefault() caches it), and Gradle's Test task
    // always forks a fresh worker JVM whose launch command line is built from this
    // config — verified by dumping the forked process command line, which showed
    // systemProperty(...) reaching that command line as a "-Duser.timezone=UTC" JVM
    // argument (not a runtime System.setProperty applied to an already-running JVM).
    // See docs/runtime-notes.md for the verification transcript.
    systemProperty("user.timezone", "UTC")
}

tasks.register<JavaExec>("keygen") {
    group = "application"
    description = "Print a fresh pair of Tink keysets for .env"
    mainClass.set("fxbot.KeygenMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.shadowJar {
    // exchange-bot-<version>.jar, not "-all" — this is the release artifact.
    archiveClassifier.set("")
    // ...but in build/release/, not build/libs/. An empty classifier gives this task the
    // same file name the plain `jar` task produces, and `installDist`, `startScripts`,
    // `distZip` and `distTar` all consume build/libs/exchange-bot-<version>.jar as `jar`'s
    // output. Two tasks writing one path is an undeclared dependency, which Gradle 9 fails
    // the build over. Giving the fat jar its own directory keeps the release file name and
    // leaves build/libs to `jar` alone; nothing depends on the fat jar's location but the
    // release workflow, which names this directory.
    destinationDirectory.set(layout.buildDirectory.dir("release"))
    // tinylog, flyway and the JDBC drivers all ship META-INF/services entries; without
    // merging, the last jar in wins and the rest silently disappear.
    mergeServiceFiles()
}
