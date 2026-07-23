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
    implementation("info.picocli:picocli:4.7.7")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.sixbridge.cli.MainKt")
    applicationName = "sixbridge"
}

tasks.test {
    useJUnitPlatform()
}
