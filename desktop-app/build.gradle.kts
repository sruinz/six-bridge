import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":bundle-contract"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material:material-icons-extended:1.7.3")
    implementation("com.google.jimfs:jimfs:1.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

val macSigningIdentity =
    providers.environmentVariable("SIXBRIDGE_MAC_SIGNING_IDENTITY")
val macAppleId =
    providers.environmentVariable("SIXBRIDGE_MAC_NOTARIZATION_APPLE_ID")
val macPassword =
    providers.environmentVariable("SIXBRIDGE_MAC_NOTARIZATION_PASSWORD")
val macTeamId =
    providers.environmentVariable("SIXBRIDGE_MAC_NOTARIZATION_TEAM_ID")
val macReleaseCredentials = listOf(
    macSigningIdentity,
    macAppleId,
    macPassword,
    macTeamId,
)
val suppliedMacReleaseCredentials = macReleaseCredentials.count { it.isPresent }
if (suppliedMacReleaseCredentials !in setOf(0, macReleaseCredentials.size)) {
    throw GradleException(
        "macOS 배포 인증 정보는 네 항목을 함께 제공해야 합니다.",
    )
}
val macReleaseCredentialsPresent =
    suppliedMacReleaseCredentials == macReleaseCredentials.size

compose.desktop {
    application {
        mainClass = "dev.sixbridge.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "SIX Bridge"
            // jpackage는 macOS app-version의 첫 숫자 0을 거부한다.
            // product metadata 0.1.3와 native package build version을 분리한다.
            packageVersion = "1.0.3"
            modules("java.net.http", "java.naming")

            macOS {
                bundleID = "dev.sixbridge.desktop"
                minimumSystemVersion = "13.0"
                iconFile.set(project.file("src/main/package-resources/six-bridge.icns"))
                if (macReleaseCredentialsPresent) {
                    signing {
                        sign.set(true)
                        identity.set(macSigningIdentity.get())
                    }
                    notarization {
                        appleID.set(macAppleId.get())
                        password.set(macPassword.get())
                        teamID.set(macTeamId.get())
                    }
                }
            }
            windows {
                upgradeUuid = "b438ea76-04ca-4aa7-af12-4b717e75551b"
                menuGroup = "SIX Bridge"
                perUserInstall = true
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    from(rootProject.layout.projectDirectory.dir("profiles")) {
        into("profiles/builtin")
    }
    from(rootProject.layout.projectDirectory.file(
        "docs/assets/six-bridge-manager-app-icon.png",
    )) {
        into("images")
        rename { "six-bridge.png" }
    }
}

val prepareDmgInstallLink by tasks.registering {
    group = "distribution"
    description = "DMG app-image에 /Applications 설치 링크를 준비합니다."
    dependsOn("createDistributable")
    outputs.upToDateWhen { false }
    doLast {
        val appImageRoot = layout.buildDirectory
            .dir("compose/binaries/main/app")
            .get().asFile.toPath()
        if (!Files.isDirectory(appImageRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw GradleException("DMG app-image root가 private directory가 아닙니다.")
        }
        val applicationsLink = appImageRoot.resolve("Applications")
        if (Files.exists(applicationsLink, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isSymbolicLink(applicationsLink) ||
                Files.readSymbolicLink(applicationsLink) != Path.of("/Applications")) {
                throw GradleException("DMG Applications 경로가 정확한 설치 링크가 아닙니다.")
            }
        } else {
            Files.createSymbolicLink(applicationsLink, Path.of("/Applications"))
        }
    }
}

tasks.configureEach {
    if (name == "packageDmg") {
        dependsOn(prepareDmgInstallLink)
    }
}

tasks.register<JavaExec>("apiProbeDraftBatch") {
    group = "verification"
    description = "API probe source 여섯 그룹을 portable .sixdraft로 생성합니다."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.sixbridge.desktop.probe.DesktopProbeDraftBatch")
    val sourceRoot = providers.gradleProperty("sixbridge.probe.source")
    val profile = providers.gradleProperty("sixbridge.probe.profile")
    val outputRoot = providers.gradleProperty("sixbridge.probe.drafts")
    doFirst {
        args(
            "--source-root", sourceRoot.get(),
            "--profile", profile.get(),
            "--output-root", outputRoot.get(),
        )
    }
}
