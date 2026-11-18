import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val platformLocalPath: String? = localProperties.getProperty("platformLocalPath")

allprojects {
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()

    repositories {
        mavenCentral()
    }
}

repositories {
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":model"))
    implementation(project(":parser"))
    implementation(project(":layout"))
    implementation(project(":export"))
    implementation(project(":render"))

    intellijPlatform {
        if (platformLocalPath != null) {
            local(platformLocalPath)
        } else {
            androidStudio(providers.gradleProperty("androidStudioVersion"))
        }
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        pluginVerifier()
    }

    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

// The extension-level jvmTarget above is not picked up by compileTestKotlin with this toolchain
// (it falls back to the JDK running Gradle), so pin every Kotlin compilation explicitly.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    // Platform interfaces (e.g. ToolWindowFactory) ship real JVM default methods. Without this the compiler
    // adds delegating bridges for every default method to our implementing classes, which the plugin verifier
    // reports as overriding/invoking deprecated and experimental API we never touch.
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
}

/**
 * Change notes for [version] as HTML, taken from its `## [version]` section in CHANGELOG.md
 * (falling back to `## [Unreleased]`). Handles the subset the changelog uses: `###` headings, `- ` bullets
 * with indented continuation lines, `**bold**` and `` `code` ``.
 */
fun changeNotesFor(version: String): String {
    val lines = rootProject.file("CHANGELOG.md").readLines()
    fun section(name: String): List<String>? {
        val start = lines.indexOfFirst { it.startsWith("## [$name]") }
        if (start < 0) return null
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end)
    }
    val body = section(version) ?: section("Unreleased") ?: return ""
    fun inline(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
        .replace(Regex("`(.+?)`"), "<code>$1</code>")

    val html = StringBuilder()
    val item = StringBuilder()
    var inList = false
    fun flushItem() {
        if (item.isNotEmpty()) html.append("<li>").append(inline(item.toString())).append("</li>")
        item.clear()
    }
    fun closeList() {
        flushItem()
        if (inList) html.append("</ul>")
        inList = false
    }
    for (line in body) {
        when {
            line.startsWith("### ") -> { closeList(); html.append("<h3>").append(inline(line.removePrefix("### "))).append("</h3>") }
            line.startsWith("- ") -> {
                flushItem()
                if (!inList) html.append("<ul>")
                inList = true
                item.append(line.removePrefix("- ").trim())
            }
            line.isNotBlank() && inList -> item.append(' ').append(line.trim())
            line.isBlank() -> flushItem()
        }
    }
    closeList()
    return html.toString()
}

intellijPlatform {
    pluginConfiguration {
        changeNotes = provider { changeNotesFor(project.version.toString()) }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // AC-22.2: no fixed upper bound.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // AC-22.1: the two supported stable Android Studio lines (see gradle.properties).
            providers.gradleProperty("pluginVerifierIdes").get()
                .split(',').map(String::trim).filter(String::isNotEmpty)
                .forEach { create(IntelliJPlatformType.AndroidStudio, it) }
        }
    }

    // Signing and publishing read their secrets from the environment only (GitHub Actions secrets in CI),
    // so nothing sensitive lives in the repository. Without the signing variables, signPlugin is skipped and
    // the unsigned zip is published.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Marketplace channel from the version's pre-release label: 0.1.0 -> default (everyone),
        // 0.1.0-eap.1 -> "eap" (only users who added that channel's repository).
        channels = providers.gradleProperty("version").map { version ->
            listOf(version.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Marketplace screenshots: `./gradlew runIdeScreenshots` opens a copy of samples/demo-music in a sandbox IDE with
// the dev-only :screenshots driver plugin, which saves PNGs to build/screenshots and closes the IDE.
// ---------------------------------------------------------------------------------------------------------------

val prepareDemoProject = tasks.register<Sync>("prepareDemoProject") {
    from("samples/demo-music") { exclude("generate_schema.py") }
    into(layout.buildDirectory.dir("demo-music"))
}

intellijPlatformTesting.runIde.register("runIdeScreenshots") {
    plugins {
        localPlugin(dependencies.project(":screenshots"))
    }
    task {
        dependsOn(prepareDemoProject)
        val demo = layout.buildDirectory.dir("demo-music")
        val out = layout.buildDirectory.dir("screenshots")
        doFirst { out.get().asFile.deleteRecursively() }
        argumentProviders.add(CommandLineArgumentProvider { listOf(demo.get().asFile.absolutePath) })
        jvmArgumentProviders.add(CommandLineArgumentProvider {
            listOf(
                "-Droomer.screenshots.out=${out.get().asFile.absolutePath}",
                "-Didea.trust.all.projects=true",
                "-Ddisable.android.first.run=true",
                "-Djb.consents.confirmation.enabled=false",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Didea.initially.ask.config=never",
            )
        })
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Rules from the acceptance criteria, enforced by the build. Both run as part of `./gradlew check`.
// ---------------------------------------------------------------------------------------------------------------

/** Kotlin/Java main sources under [dir], as (path relative to the root, text) pairs. */
fun mainSourcesOf(dir: File): List<Pair<String, String>> =
    dir.resolve("src/main").walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
        .map { it.relativeTo(rootDir).path to it.readText() }
        .toList()

/**
 * AC-24.1 / NFR-07: the UI-free logic modules must not use Swing, AWT or the IntelliJ Platform.
 * The module graph already keeps the platform off their classpath; this also catches JDK Swing/AWT imports.
 */
val checkUiFreeModules by tasks.registering {
    group = "verification"
    description = "Fails if the model, parser, layout or export modules reference Swing, AWT or the IntelliJ Platform."
    val dirs = listOf("model", "parser", "layout", "export").map { rootDir.resolve(it) }
    inputs.files(dirs.map { it.resolve("src/main") })
    doLast {
        val forbidden = Regex("""^\s*import\s+(javax\.swing|java\.awt|com\.intellij|org\.jetbrains\.intellij)\b""", RegexOption.MULTILINE)
        val problems = dirs.flatMap { dir ->
            mainSourcesOf(dir).flatMap { (path, text) -> forbidden.findAll(text).map { "$path: ${it.value.trim()}" }.toList() }
        }
        if (problems.isNotEmpty()) throw GradleException("UI-free modules reference UI or IDE classes:\n" + problems.joinToString("\n"))
    }
}

/**
 * AC-23.2 / NFR-03: no HTTP client, telemetry or analytics, neither as a bundled dependency nor in our own code.
 * The single documentation link is opened with BrowserUtil on user click (AC-23.3); that is not a network call.
 */
val checkNoNetworkAccess by tasks.registering {
    group = "verification"
    description = "Fails if a bundled dependency or the plugin's own code is an HTTP client or telemetry library."
    val runtime = configurations.named("runtimeClasspath")
    val dirs = listOf("model", "parser", "layout", "export", "render").map { rootDir.resolve(it) } + rootDir
    inputs.files(dirs.map { it.resolve("src/main") })
    doLast {
        val forbiddenArtifacts = Regex(
            "okhttp|retrofit|ktor-client|httpclient|httpcore|async-http|jetty-client|netty|grpc|" +
                "firebase|crashlytics|sentry|bugsnag|segment|mixpanel|amplitude|analytics|telemetry|opentelemetry",
            RegexOption.IGNORE_CASE,
        )
        val bundled = runtime.get().resolvedConfiguration.resolvedArtifacts
            .map { it.moduleVersion.id.let { id -> "${id.group}:${id.name}:${id.version}" } }
        val badArtifacts = bundled.filter { forbiddenArtifacts.containsMatchIn(it) }

        val forbiddenCode = Regex(
            """\b(java\.net\.http|HttpURLConnection|HttpsURLConnection|java\.net\.Socket|java\.net\.URL\b|""" +
                """\.toURL\(\)|openConnection\(|openStream\(|com\.intellij\.util\.io\.HttpRequests|""" +
                """FeatureUsageTracker|com\.intellij\.internal\.statistic)""",
        )
        val badCode = dirs.flatMap { dir ->
            mainSourcesOf(dir).flatMap { (path, text) ->
                text.lineSequence().withIndex()
                    .filter { (_, line) -> forbiddenCode.containsMatchIn(line) }
                    .map { (i, line) -> "$path:${i + 1}: ${line.trim()}" }.toList()
            }
        }
        val problems = badArtifacts.map { "bundled dependency: $it" } + badCode
        if (problems.isNotEmpty()) throw GradleException("Network or telemetry access found:\n" + problems.joinToString("\n"))
    }
}

tasks.named("check") { dependsOn(checkUiFreeModules, checkNoNetworkAccess) }
