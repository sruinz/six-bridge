plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    id("androidx.room") version "2.8.4" apply false
}

allprojects {
    group = "dev.sixbridge"
    version = "0.1.0-SNAPSHOT"
}
