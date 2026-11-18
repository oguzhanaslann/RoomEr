rootProject.name = "RoomEr"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// UI-free logic modules (no Swing, no IntelliJ dependencies — see spec NFR-07 / AC-24.1)
include(":model")   // schema model, cardinality inference, search
include(":parser")  // Room JSON -> model
include(":layout")  // LayoutEngine interface + ELK implementation
include(":export")  // Mermaid generator

// Swing canvas. Depends on Swing only, not on the IntelliJ platform.
include(":render")

// Dev-only helper plugin that captures the Marketplace screenshots (`./gradlew runIdeScreenshots`). Never shipped.
include(":screenshots")
project(":screenshots").projectDir = file("tools/screenshots")
