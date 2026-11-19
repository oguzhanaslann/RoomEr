plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("androidx.room3")
}

android {
    namespace = "com.example.books"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.books"
        minSdk = 26
        targetSdk = 37
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.room3:room3-runtime:3.0.3")
    ksp("androidx.room3:room3-compiler:3.0.3")
}
