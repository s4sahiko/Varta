// Top-level build file. Individual module configuration lives in app/build.gradle.kts
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Serialization plugin declared here so the app module can apply it
    // without re-stating the version (avoids "version conflict" lint warning).
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    // Bug-fix: rootProject.buildDir is deprecated in Gradle 8 and removed in 9.
    delete(layout.buildDirectory.get().asFile)
}
