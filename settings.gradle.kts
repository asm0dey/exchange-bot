plugins {
    // Lets Gradle provision a JDK 25 toolchain on its own (via the Foojay Disco API)
    // when the machine only has other JDK versions installed — this build targets
    // Java 25 (see build.gradle.kts `kotlin { jvmToolchain(25) }`) but the CI/build
    // hosts are not guaranteed to have it pre-installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "exchange-bot"
