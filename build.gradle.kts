plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    application
}

repositories { mavenCentral() }

dependencies {
    implementation(libs.telegram.bot)
    ksp(libs.ktnip)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
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
    testImplementation(libs.ktor.client.mock)
}

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
