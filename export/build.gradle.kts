import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    implementation(project(":model"))
    // Provided by the IDE at runtime; never bundled (see gradle.properties).
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
    testImplementation("junit:junit:4.13.2")
    // Golden tests parse the parser's JSON fixtures end to end (AC-19.1). Test scope only.
    testImplementation(project(":parser"))
}

// One set of JSON fixtures for the whole project: reuse the parser's.
sourceSets.test {
    resources.srcDir(rootProject.file("parser/src/test/resources"))
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}
