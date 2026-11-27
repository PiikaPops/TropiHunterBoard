import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
    // Controlify (optional controller support, compile only)
    maven("https://api.modrinth.com/maven") { content { includeGroup("maven.modrinth") } }
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
    // ...and in the development client only (`gradlew runClient`); never bundled in the jar
    modLocalRuntime("com.cobblemon:fabric:${project.property("cobblemon_version")}")

    // Controlify 3.0.1 LTS for 1.21.1: compile only, from the copy prepared below. HunterBoard's
    // controller support (com.hunterboard.controller.controlify) only loads through Controlify's
    // own "controlify" entrypoint, so nothing of it runs, or is even loaded, without Controlify.
    modCompileOnly(files(controlifyForCompile()))

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}

/**
 * Controlify's jar (Modrinth version y9bu5RxH), made readable by this Loom: it was built with
 * Loom 1.14, which this one (1.9) refuses, and carries a "class tweaker" it cannot parse. The
 * copy drops those manifest lines, the class tweaker and the nested jars; the classes are
 * untouched. Used to compile against only, never shipped.
 */
fun controlifyForCompile(): File {
    val out = layout.buildDirectory.file("controlify-compile/controlify-3.0.1-compile.jar").get().asFile
    if (out.exists()) return out
    val raw = configurations.detachedConfiguration(dependencies.create("maven.modrinth:controlify:y9bu5RxH"))
        .apply { isTransitive = false }.singleFile
    out.parentFile.mkdirs()
    ZipFile(raw).use { zip ->
        ZipOutputStream(out.outputStream()).use { dst ->
            for (entry in zip.entries()) {
                val name = entry.name
                if (name.startsWith("META-INF/jars/") || name.endsWith(".ct")) continue
                var bytes = zip.getInputStream(entry).readBytes()
                if (name == "META-INF/MANIFEST.MF") {
                    bytes = bytes.toString(Charsets.UTF_8).lines().filterNot { it.startsWith("Fabric-Loom-") }.joinToString("\n").toByteArray()
                } else if (name == "fabric.mod.json") {
                    @Suppress("UNCHECKED_CAST")
                    val json = groovy.json.JsonSlurper().parseText(bytes.toString(Charsets.UTF_8)) as MutableMap<String, Any?>
                    json.remove("accessWidener")
                    json.remove("jars")
                    bytes = groovy.json.JsonOutput.toJson(json).toByteArray()
                }
                dst.putNextEntry(ZipEntry(name))
                dst.write(bytes)
                dst.closeEntry()
            }
        }
    }
    return out
}

// Development tools (src/dev: UiTour, WikiAudit) are a mod of their own, "hunterboard_dev",
// that only the development client loads. The released jar is built from src/main alone.
val dev: SourceSet = sourceSets.create("dev") {
    compileClasspath += sourceSets.main.get().compileClasspath + sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().runtimeClasspath + sourceSets.main.get().output
}
kotlin.target.compilations.getByName("dev").associateWith(kotlin.target.compilations.getByName("main"))

// `gradlew runClient -Puitour=<folder>` photographs every screen into <folder> and quits
// (see UiTour.kt). -Puitour.themes=a,b and -Puitour.only=a,b narrow it down.
// Cobblemon 1.8.1 wants a newer loader and Fabric API than this build pins, so the
// development client also needs: -Ploader_version=0.17.2 -Pfabric_version=0.116.6+1.21.1
loom {
    mods {
        register("hunterboard") { sourceSet(sourceSets.main.get()) }
        register("hunterboard_dev") { sourceSet(dev) }
    }
    runs {
        named("client") {
            source(dev)
            // developer mode: /hbdebug (see DevMode.kt), on unless -Pdev=false; a player adds the
            // same JVM argument to their launcher
            vmArg("-Dhunterboard.dev=${findProperty("dev") ?: "true"}")
            (findProperty("uitour") as String?)?.let { dir ->
                vmArg("-Dhunterboard.uitour=$dir")
                (findProperty("uitour.themes") as String?)?.let { vmArg("-Dhunterboard.uitour.themes=$it") }
                (findProperty("uitour.only") as String?)?.let { vmArg("-Dhunterboard.uitour.only=$it") }
                (findProperty("uitour.audit") as String?)?.let { vmArg("-Dhunterboard.uitour.audit=$it") }
                (findProperty("uitour.pce2e") as String?)?.let { vmArg("-Dhunterboard.uitour.pce2e=$it") }
                (findProperty("uitour.habitatfeed") as String?)?.let { vmArg("-Dhunterboard.uitour.habitatfeed=$it") }
                (findProperty("uitour.controlify") as String?)?.let { vmArg("-Dhunterboard.uitour.controlify=$it") }
                (findProperty("uitour.raid") as String?)?.let { vmArg("-Dhunterboard.uitour.raid=$it") }
                (findProperty("uitour.painting") as String?)?.let { vmArg("-Dhunterboard.uitour.painting=$it") }
                (findProperty("uitour.devmode") as String?)?.let { vmArg("-Dhunterboard.uitour.devmode=$it") }
                val size = ((findProperty("uitour.size") as String?) ?: "960x540").split("x")
                programArgs("--width", size[0], "--height", size[1])
            }
        }
    }
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
