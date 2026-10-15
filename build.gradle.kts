plugins {
    id("fabric-loom") version "1.9-SNAPSHOT"
    id("maven-publish")
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

// Cobblemon release this build is compiled against ("1.8.0+1.21.1" -> "1.8.0").
// It goes in the ARCHIVE NAME only: fabric.mod.json keeps the plain mod version,
// because UpdateChecker.isNewer() parses it numerically and a suffix would skew it.
val cobblemonCompat = (project.property("cobblemon_version") as String).substringBefore("+")

tasks.withType<AbstractArchiveTask>().configureEach {
    archiveVersion.set("${project.version}+cobblemon.$cobblemonCompat")
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    maven("https://maven.impactdev.net/repository/development/")
}

dependencies {
    // Minecraft and Fabric
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")
    mappings("net.fabricmc:yarn:${project.property("yarn_mappings")}:v2")
    modImplementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Kotlin
    modImplementation("net.fabricmc:fabric-language-kotlin:${project.property("fabric_kotlin_version")}")

    // Cobblemon (compile only - provided at runtime)
    modCompileOnly("com.cobblemon:fabric:${project.property("cobblemon_version")}")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}
