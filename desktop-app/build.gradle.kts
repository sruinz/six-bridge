import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
            // product metadata 0.1.0과 native package build version을 분리한다.
            packageVersion = "1.0.0"
            modules("java.net.http", "java.naming")

            macOS {
                bundleID = "dev.sixbridge.desktop"
                minimumSystemVersion = "13.0"
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
}
