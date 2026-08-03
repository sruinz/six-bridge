import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":api-probe"))
    implementation("com.google.jimfs:jimfs:1.3.1")
    implementation("info.picocli:picocli:4.7.7")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application {
    mainClass.set("dev.sixbridge.cli.MainKt")
    applicationName = "sixbridge"
}

tasks.test {
    useJUnitPlatform()
}
