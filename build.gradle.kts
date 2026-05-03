import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    // Shadow plugin disabled in this environment due to ZIP/META-INF write issues
    // id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.sentrysmp"
version = "1.3"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.luckperms.net/repository/everything/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    compileOnly("net.luckperms:api:5.4")
    compileOnly("org.apache.logging.log4j:log4j-core:2.24.1")

    implementation("io.ktor:ktor-server-core:3.4.0")
    implementation("io.ktor:ktor-server-netty:3.4.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.0")
    implementation("io.ktor:ktor-server-swagger:3.4.0")
    implementation("io.ktor:ktor-server-routing-openapi:3.4.0")
    implementation("io.ktor:ktor-server-cors:3.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:3.4.0")
}

tasks {
    withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java) {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
}

// Shadow configuration removed; use the `fatJar` fallback task below when shadowJar fails.

// Fallback fat JAR task (no package relocation). Use when shadowJar fails in this environment.
val fatJar by tasks.registering(org.gradle.jvm.tasks.Jar::class) {
    archiveClassifier.set("all")
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from({ configurations.runtimeClasspath.get().filter { it.exists() }.map { if (it.isDirectory) it else zipTree(it) } })
}
