import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // Eclipse Layout Kernel (EPL-2.0), layered algorithm only. Bundled with the plugin.
    implementation("org.eclipse.elk:org.eclipse.elk.core:0.12.1")
    implementation("org.eclipse.elk:org.eclipse.elk.graph:0.12.1")
    implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.12.1")
    // ELK's generated option classes (e.g. LayeredMetaDataProvider) call Xbase runtime helpers, but the ELK
    // 0.12.1 POMs do not declare this dependency. 2.36.0 is the Xtext version ELK 0.12.1 was built with.
    // Not transitive: its only dependency is Guava, which is pinned below.
    implementation("org.eclipse.xtext:org.eclipse.xtext.xbase.lib:2.36.0") { isTransitive = false }
    // ELK asks for Guava "[15.0,)", an open range that would float to whatever is newest. Pin it.
    implementation("com.google.guava:guava") { version { strictly("33.8.0-jre") } }
    // Provided by the IDE at runtime; never bundled (see gradle.properties).
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
}

configurations.named("implementation") {
    // Not needed to run a layout (the test suite runs without them): XMI (de)serialisation of ELK graphs, and
    // Guava's compile-time-only annotation artifacts. Declared on `implementation` so the exclusions also
    // apply to the test runtime and to modules that consume :layout.
    exclude(group = "org.eclipse.emf", module = "org.eclipse.emf.ecore.xmi")
    exclude(group = "com.google.guava", module = "listenablefuture")
    exclude(group = "org.jspecify", module = "jspecify")
    exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    exclude(group = "com.google.j2objc", module = "j2objc-annotations")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

tasks.withType<Test>().configureEach {
    // One JVM per test class, so ElkLayoutPerformanceTest measures a genuinely cold first ELK call.
    forkEvery = 1
    testLogging {
        showStandardStreams = true
    }
}
