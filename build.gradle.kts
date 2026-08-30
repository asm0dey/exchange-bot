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

kotlin { jvmToolchain(21) }

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
