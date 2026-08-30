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
    implementation(libs.ktor.serialization.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.db.scheduler)
    implementation(libs.h2)
    implementation(libs.hikari)
    implementation(libs.tink)
    implementation(libs.flyway.core)
    implementation(libs.slf4j.simple)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)

    testImplementation(libs.kotest.runner)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.ktor.client.mock)
}

kotlin { jvmToolchain(21) }

application { mainClass.set("fxbot.MainKt") }

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("keygen") {
    group = "application"
    description = "Print a fresh pair of Tink keysets for .env"
    mainClass.set("fxbot.KeygenMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}
