plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.shadow)
    alias(libs.plugins.gradle.bun)
    application
}

// `application` pulls in the `java` plugin, which the Kotlin DSL exposes as a `java`
// extension accessor on Project — shadowing the `java.*` package prefix inside this script.
// These imports are how verifyBunArchive below reaches java.net.URI / java.security.MessageDigest.
import java.net.URI
import java.security.MessageDigest

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

// The mini app (web/) is a Vue + Vite project with npm dependencies locked in web/bun.lock.
// This plugin downloads a pinned Bun into .gradle/bun/ and runs it from there — nobody
// installs Bun by hand, and a bot-only contributor needs nothing beyond the JDK. The archive
// it downloads is checked against a pinned SHA-256 below (bun.downloadBaseUrl) before
// bunSetup ever unpacks it.
val bunVersion = "1.3.14"
val bunVerifiedDir = layout.buildDirectory.dir("bun-verified")

bun {
    version.set(bunVersion)
    workingDir.set(layout.projectDirectory.dir("web"))
    // toURI(), not "file://${absolutePath}" — the latter yields "file://C:\..." on Windows,
    // which URI(String) (used by bunSetup below) rejects. toURI() is correct on every OS.
    // BunSetupTask appends "/bun-v<version>/<archiveFileName>" itself, so no trailing slash.
    downloadBaseUrl.set(bunVerifiedDir.map { it.asFile.toURI().toString().removeSuffix("/") })
}

// Pinned from https://github.com/oven-sh/bun/releases/download/bun-v1.3.14/SHASUMS256.txt
// (cross-checked against `gh api repos/oven-sh/bun/releases/tags/bun-v1.3.14`'s per-asset
// digests). Renovate (github-release-attachments datasource, wired in Task 12) bumps
// bunVersion above and every digest below together, one release at a time — one asset per
// line, filename and full digest on that line, so its regex manager can match either.
// Bumping bunVersion without adding that release's digests here fails verifyBunArchive with
// a clear "no pinned SHA-256" error rather than silently trusting an unchecked download.
val bunDigests = mapOf(
    "bun-darwin-aarch64.zip" to "d8b96221828ad6f97ac7ac0ab7e95872341af763001e8803e8267652c2652620",
    "bun-darwin-x64.zip" to "4183df3374623e5bab315c547cfa0974533cd457d86b73b639f7a87974cd6633",
    "bun-linux-aarch64.zip" to "a27ffb63a8310375836e0d6f668ae17fa8d8d18b88c37c821c65331973a19a3b",
    "bun-linux-x64.zip" to "951ee2aee855f08595aeec6225226a298d3fea83a3dcd6465c09cbccdf7e848f",
    "bun-windows-x64.zip" to "0a0620930b6675d7ba440e81f4e0e00d3cfbe096c4b140d3fff02205e9e18922",
)

// BunSetupTask deletes the zip right after extracting it, so there is nothing left to check
// after the fact. Instead, this task re-downloads the exact same archive to a local path
// FIRST, verifies its digest, and bun.downloadBaseUrl (above) points bunSetup at that
// verified copy instead of the network — bunSetup then reads bytes this task already checked.
val verifyBunArchive = tasks.register("verifyBunArchive") {
    group = "bun"
    description = "Downloads the Bun archive for this platform and checks it against the pinned SHA-256 before bunSetup trusts it."
    val platform = io.clroot.gradle.bun.platform.Platform.current()
    val archiveFile = bunVerifiedDir.get().dir("bun-v$bunVersion").file(platform.archiveFileName).asFile
    // Copied into plain locals so doLast's closure captures values, not a reference back to
    // this script's own object (top-level script properties aren't config-cache-serializable).
    val version = bunVersion
    val expectedDigest = bunDigests[platform.archiveFileName]
        ?: error("No pinned SHA-256 for bun $version / ${platform.archiveFileName}; add one before bumping the version.")
    // Real inputs, not just the output file: editing a digest for the same version must
    // invalidate this task, or it stays UP-TO-DATE once the (now-wrongly-verified) archive
    // already exists on disk.
    inputs.property("version", version)
    inputs.property("sha256", expectedDigest)
    outputs.file(archiveFile)
    doLast {
        archiveFile.parentFile.mkdirs()
        val url = "https://github.com/oven-sh/bun/releases/download/bun-v$version/${platform.archiveFileName}"
        URI(url).toURL().openStream().use { it.copyTo(archiveFile.outputStream()) }
        val actual = MessageDigest.getInstance("SHA-256").digest(archiveFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expectedDigest) { "Bun archive checksum mismatch for ${platform.archiveFileName}: expected $expectedDigest, got $actual" }
    }
}

tasks.named<io.clroot.gradle.bun.task.BunSetupTask>("bunSetup") {
    dependsOn(verifyBunArchive)
    // BunSetupTask returns early when the executable at installDir already exists — a
    // leftover from an earlier run, a CI cache restore, or a hand-placed binary would then
    // run without ever being extracted from the archive verifyBunArchive just checked.
    // Deleting first forces every execution to (re-)extract from the verified archive; the
    // task's @OutputDirectory installDir means Gradle still reruns it once the directory no
    // longer matches its last snapshot.
    doFirst { installDir.get().asFile.deleteRecursively() }
}

val webBuild = tasks.register<io.clroot.gradle.bun.task.BunTask>("webBuild") {
    description = "Builds the mini app into build/web/static"
    dependsOn("bunInstall")
    args("run", "build")
    inputs.dir("web/src")
    inputs.dir("web/e2e")
    inputs.files("web/index.html", "web/vite.config.ts", "web/tsconfig.json", "web/package.json", "web/bun.lock", "web/playwright.config.ts")
    outputs.dir(layout.buildDirectory.dir("web"))
}

val webTest = tasks.register<io.clroot.gradle.bun.task.BunTask>("webTest") {
    description = "Runs the mini app's unit tests (vitest)"
    dependsOn("bunInstall")
    args("run", "test")
    inputs.dir("web/src")
    outputs.upToDateWhen { false }
}

// Playwright needs a browser, so it is not part of `check`; CI runs it explicitly.
val webE2e = tasks.register<io.clroot.gradle.bun.task.BunTask>("webE2e") {
    description = "Runs the mini app's Playwright tests against mock data"
    dependsOn("bunInstall")
    args("run", "e2e")
    // -PwebE2eArgs=--update-snapshots reaches `playwright test` (not `playwright install`,
    // the other half of the `e2e` script) because "--" only separates it from `bun run e2e`'s
    // own args; both script commands still share the one trailing argument list.
    args.addAll(providers.gradleProperty("webE2eArgs").map { listOf("--", it) }.orElse(emptyList()))
    outputs.upToDateWhen { false }
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
