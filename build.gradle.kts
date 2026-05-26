import org.gradle.api.tasks.JavaExec

plugins {
    `maven-publish`
    id("hytale-mod") version "0.+"
}

group = "com.hexvane"
version = "2.2.1"
val javaVersion = 25

repositories {
    mavenCentral()
    maven("https://maven.hytale-modding.info/releases") {
        name = "HytaleModdingReleases"
    }
    // Alec's Tamework! JAR for compileOnly (matches https://www.curseforge.com/hytale/mods/alecs-tamework/files)
    maven("https://cursemaven.com") {
        name = "CurseMaven"
    }
}

val tameworkCurseFileId: String =
    findProperty("tamework_curse_file_id")?.toString() ?: "8149204"

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    compileOnly(libs.jetbrains.annotations)
    compileOnly(libs.jspecify)
    // Compile against Alec's Tamework! — place the matching JAR in the server's mods/ folder yourself at runtime.
    compileOnly("curse.maven:alecs-tamework-1447962:$tameworkCurseFileId")
}

hytale {
    // uncomment if you want to add the Assets.zip file to your external libraries;
    // ⚠️ CAUTION, this file is very big and might make your IDE unresponsive for some time!
    //
    // addAssetsDependency = true

    // uncomment if you want to develop your mod against the pre-release version of the game.
    //
    //updateChannel = "pre-release"

    // Dragonlings depends on Alec's Tamework!: place the matching Tamework JAR in the server's mods/ folder when testing or deploying.
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion)
    }

    withSourcesJar()
}

tasks.named<ProcessResources>("processResources") {
    var replaceProperties = mapOf(
        "plugin_group" to findProperty("plugin_group"),
        "plugin_maven_group" to project.group,
        "plugin_name" to project.name,
        "plugin_version" to project.version,
        "server_version" to findProperty("server_version"),

        "plugin_description" to findProperty("plugin_description"),
        "plugin_website" to findProperty("plugin_website"),

        "plugin_main_entrypoint" to findProperty("plugin_main_entrypoint"),
        "plugin_author" to findProperty("plugin_author"),

        "tamework_manifest_version" to findProperty("tamework_manifest_version")
    )

    filesMatching("manifest.json") {
        expand(replaceProperties)
    }

    inputs.properties(replaceProperties)
}

tasks.withType<Jar> {
    manifest {
        attributes["Specification-Title"] = rootProject.name
        attributes["Specification-Version"] = version
        attributes["Implementation-Title"] = project.name
        attributes["Implementation-Version"] =
            providers.environmentVariable("COMMIT_SHA_SHORT")
                .map { "${version}-${it}" }
                .getOrElse(version.toString())
    }
}

publishing {
    repositories {
        // This is where you put repositories that you want to publish to.
        // Do NOT put repositories for your dependencies here.
    }

    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// IDEA no longer automatically downloads sources/javadoc jars for dependencies, so we need to explicitly enable the behavior.
idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val syncAssets = tasks.register<Copy>("syncAssets") {
    group = "hytale"
    description = "Automatically syncs assets from Build back to Source after server stops."

    // Take from the temporary build folder (Where the game saved changes)
    from(layout.buildDirectory.dir("resources/main"))

    // Copy into your actual project source (Where your code lives)
    into("src/main/resources")

    // IMPORTANT: Protect the manifest template from being overwritten
    exclude("manifest.json")

    // If a file exists, overwrite it with the new version from the game
    duplicatesStrategy = DuplicatesStrategy.INCLUDE

    doLast {
        println("✅ Assets successfully synced from Game to Source Code!")
    }
}

afterEvaluate {
    val runServerTask = tasks.findByName("runServer") ?: tasks.findByName("server")
    if (runServerTask == null) {
        logger.warn("⚠️ Could not find 'runServer' or 'server' task (hytale-mod). syncAssets not hooked.")
        return@afterEvaluate
    }
    if (runServerTask !is JavaExec) {
        logger.warn("⚠️ Task '${runServerTask.name}' is not JavaExec; skipping sync hook and runServerNoSync.")
        return@afterEvaluate
    }
    val runServer = runServerTask as JavaExec
    // hytale-mod 0.7.x always adds an empty jvmArg when HytaleServer.aot is missing; on Windows Gradle's
    // JavaExec then fails with "Could not find or load main class" (empty ClassNotFoundException).
    runServer.jvmArgs = runServer.jvmArgs.filter { it.isNotBlank() }
    runServer.finalizedBy(syncAssets)
    logger.lifecycle("✅ Task '${runServer.name}' is finalized by syncAssets.")

    tasks.register<JavaExec>("runServerNoSync") {
        group = "hytale"
        description =
            "Same as runServer but does not run syncAssets afterward — safe when you edit src/main/resources while testing."
        classpath = runServer.classpath
        mainClass = runServer.mainClass
        mainModule = runServer.mainModule
        modularity.inferModulePath = runServer.modularity.inferModulePath
        jvmArgs = runServer.jvmArgs.filter { it.isNotBlank() }
        workingDir = runServer.workingDir
        args = runServer.args
        systemProperties = runServer.systemProperties
        environment = runServer.environment
        standardInput = runServer.standardInput
        isIgnoreExitValue = runServer.isIgnoreExitValue
        javaLauncher = runServer.javaLauncher
        enableAssertions = runServer.enableAssertions
    }
    logger.lifecycle("✅ Task 'runServerNoSync' registered (no post-exit asset sync).")
}
