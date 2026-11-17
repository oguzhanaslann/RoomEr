import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    api(project(":model"))
    api(project(":layout"))
    // Provided by the IDE at runtime; never bundled (see gradle.properties).
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
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

tasks.test {
    // The canvas is exercised without a display (painting into BufferedImages).
    systemProperty("java.awt.headless", "true")
    testLogging {
        showStandardStreams = true
    }
}

// Paints the demo diagram to PNG files (light/dark, selection, focus mode, …) in build/demo for visual checks.
tasks.register<JavaExec>("renderDemo") {
    group = "verification"
    description = "Renders the demo ER diagram to PNG files in render/build/demo"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.oguzhanaslann.roomer.render.demo.DemoRenderer")
    systemProperty("java.awt.headless", "true")
    val out = layout.buildDirectory.dir("demo")
    outputs.dir(out)
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.absolutePath) })
}

// Opens the demo diagram in a window (for humans).
tasks.register<JavaExec>("runDemo") {
    group = "verification"
    description = "Opens the demo ER diagram in a JFrame"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.oguzhanaslann.roomer.render.demo.DemoApp")
}
