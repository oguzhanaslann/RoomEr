import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":model"))
    implementation("com.google.code.gson:gson:2.13.2") {
        // Compile-time annotations only; not needed in the plugin distribution.
        exclude(group = "com.google.errorprone", module = "error_prone_annotations")
    }
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
