import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// Dev-only plugin that drives a sandbox IDE and saves the Marketplace screenshots. It is loaded only by the
// root project's `runIdeScreenshots` task and is never part of the RoomEr plugin zip.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // Loaded at runtime from the RoomEr plugin's classloader (plugin.xml depends on it).
    compileOnly(project(":render"))
    compileOnly(project(":export"))

    intellijPlatform {
        val local = localProperties.getProperty("platformLocalPath")
        if (local != null) local(local) else androidStudio(providers.gradleProperty("androidStudioVersion"))
    }
}

intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// As in the root project: pin every Kotlin compilation, the extension-level jvmTarget is not always applied.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
}

// This plugin is a dev tool: never verify, sign or publish it (and keep it out of release runs of those tasks).
listOf("verifyPlugin", "signPlugin", "publishPlugin", "verifyPluginSignature").forEach { name ->
    tasks.named(name) { enabled = false }
}
