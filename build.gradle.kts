import com.github.gradle.node.npm.task.NpmTask
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.internal.os.OperatingSystem
import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.shadow)
    alias(libs.plugins.node.gradle)
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

// Node itself is NOT what runs the mini app's scripts (Bun is, below) — it exists purely so
// vue-tsc, vite, vitest and playwright's bin scripts (every one starts `#!/usr/bin/env node`)
// have a real `node` to be exec'd by. Confirmed the hard way: under Bun's own JS runtime,
// `vue-tsc`'s TypeScript plugin never registers its `.vue`-import resolver, and every `.vue`
// import fails with "Cannot find module './App.vue'" — this reproduces with `bunx vue-tsc`,
// `bunx --bun vue-tsc` and `bun --bun x vue-tsc` alike, even with a system Node installed and
// on PATH, so no Bun-side flag fixes it; only a real Node process running the script does. See
// the Task 12 fix-round-1 report for the reproduction. `com.github.node-gradle.node` downloads
// a pinned Node release so this doesn't depend on the host machine having one.
node {
    download.set(true)
    version.set("24.21.0")
    workDir.set(layout.projectDirectory.dir(".gradle/nodejs"))
    npmWorkDir.set(layout.projectDirectory.dir(".gradle/npm"))
}

// The mini app (web/) is a Vue + Vite project with its own dependencies locked in
// web/bun.lock and run with Bun — nobody installs Bun by hand, and a bot-only contributor
// needs nothing beyond the JDK (node-gradle above fetches Node; this fetches Bun). Bun
// itself comes from npm, not a hand-verified download: `web/tools/package.json` pins the
// exact `bun` version as its only dependency, and its committed `package-lock.json` records
// npm's own sha512 integrity for it AND for whichever `@oven/bun-<platform>` binary package
// matches the machine running `npm ci` — the same verification npm gives any dependency, no
// bespoke digest table to keep in sync by hand. Renovate's npm manager bumps
// web/tools/package.json + its lockfile natively; no custom manager needed for Bun.
//
// `bun`'s own postinstall script (which copies the right platform binary into
// node_modules/bun/bin/) needs explicit approval under npm's install-scripts allowlist —
// already recorded in web/tools/package.json's "allowScripts" field (committed), so `npm ci`
// runs it unattended everywhere, regardless of a machine's global npm config.
val bunToolInstall = tasks.register<NpmTask>("bunToolInstall") {
    description = "Installs the pinned Bun binary from npm (web/tools), sha512-verified by npm ci."
    workingDir.set(layout.projectDirectory.dir("web/tools").asFile)
    npmCommand.set(listOf("ci"))
    inputs.files("web/tools/package.json", "web/tools/package-lock.json")
    outputs.dir(layout.projectDirectory.dir("web/tools/node_modules"))
}

// Where bunToolInstall above puts the Bun binary npm installed, and where node-gradle put
// the Node it downloaded. Every Bun invocation below runs with the Bun dir first (so
// `commandLine` finds Bun without a hardcoded platform-specific executable name beyond this
// one place) and the Node dir right after (so the shebang'd scripts Bun then execs find a
// real `node`) — ahead of the inherited PATH, so neither depends on (or can be shadowed by)
// whatever Node the host machine happens to have, matching the "no system Node" proof this
// task requires.
val bunBinDir = layout.projectDirectory.dir("web/tools/node_modules/.bin")
val bunExecutableName = if (OperatingSystem.current().isWindows) "bun.cmd" else "bun"
val nodeBinDir: Provider<Directory> = node.resolvedNodeDir.map {
    if (OperatingSystem.current().isWindows) it else it.dir("bin")
}

fun Exec.runBun(vararg bunArgs: String) {
    dependsOn(bunToolInstall, "nodeSetup")
    workingDir(layout.projectDirectory.dir("web"))
    commandLine(bunBinDir.file(bunExecutableName).asFile.absolutePath, *bunArgs)
    val pathWithNodeAndBun = listOf(
        nodeBinDir.get().asFile.absolutePath,
        bunBinDir.asFile.absolutePath,
        System.getenv("PATH").orEmpty(),
    ).joinToString(File.pathSeparator)
    environment("PATH", pathWithNodeAndBun)
}

val bunInstall = tasks.register<Exec>("bunInstall") {
    description = "Installs the mini app's own dependencies (web/bun.lock) with Bun."
    runBun("install", "--frozen-lockfile")
    inputs.files("web/package.json", "web/bun.lock")
    outputs.dir(layout.projectDirectory.dir("web/node_modules"))
}

val webBuild = tasks.register<Exec>("webBuild") {
    description = "Builds the mini app into build/web/static"
    dependsOn(bunInstall)
    runBun("run", "build")
    inputs.dir("web/src")
    inputs.dir("web/e2e")
    inputs.files("web/index.html", "web/vite.config.ts", "web/tsconfig.json", "web/package.json", "web/bun.lock", "web/playwright.config.ts")
    outputs.dir(layout.buildDirectory.dir("web"))
}

val webTest = tasks.register<Exec>("webTest") {
    description = "Runs the mini app's unit tests (vitest)"
    dependsOn(bunInstall)
    runBun("run", "test")
    inputs.dir("web/src")
    outputs.upToDateWhen { false }
}

// Playwright needs a browser, so it is not part of `check`; CI runs it explicitly.
val webE2e = tasks.register<Exec>("webE2e") {
    description = "Runs the mini app's Playwright tests against mock data"
    dependsOn(bunInstall)
    // -PwebE2eArgs=--update-snapshots reaches `playwright test` (not `playwright install`,
    // the other half of the `e2e` script) because "--" only separates it from `bun run e2e`'s
    // own args; both script commands still share the one trailing argument list.
    val extra = providers.gradleProperty("webE2eArgs").orNull
    val extraArgs = if (extra != null) arrayOf("--", extra) else emptyArray()
    runBun("run", "e2e", *extraArgs)
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
