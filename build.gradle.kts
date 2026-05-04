import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    kotlin("plugin.serialization") version libs.versions.kotlin.get()
    // Shadow plugin disabled in this environment due to ZIP/META-INF write issues
    // id("com.github.johnrengelman.shadow") version libs.versions.shadow.get()
}

group = "com.sentrysmp"
version = "1.4"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.luckperms.net/repository/everything/")
}

dependencies {
    val ktorVersion = libs.versions.ktor.get()
    val coroutinesVersion = libs.versions.coroutines.get()
    val serializationVersion = libs.versions.serialization.get()
    val log4jVersion = libs.versions.log4j.get()
    val paperVersion = libs.versions.paper.get()
    val luckpermsVersion = libs.versions.luckperms.get()

    compileOnly("io.papermc.paper:paper-api:$paperVersion")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    compileOnly("net.luckperms:api:$luckpermsVersion")
    compileOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-server-swagger:$ktorVersion")
    implementation("io.ktor:ktor-server-routing-openapi:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
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
