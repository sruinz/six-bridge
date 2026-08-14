import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val releaseStorePassword = providers.environmentVariable(
    "SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD",
)
val releaseKeyPassword = providers.environmentVariable(
    "SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD",
)
val releaseKeystore = rootProject.layout.projectDirectory.file(
    ".local/keystore/six-bridge-manager-release.p12",
)
val releaseKeyAlias = "six-bridge-manager"

fun releaseKeystorePathIssue(): String? {
    val projectRoot = rootProject.layout.projectDirectory.asFile.toPath()
        .toAbsolutePath()
        .normalize()
    if (Files.isSymbolicLink(projectRoot) ||
        !Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)
    ) {
        return "project root가 symbolic link가 아닌 directory여야 합니다: $projectRoot"
    }

    val canonicalRoot = try {
        projectRoot.toRealPath()
    } catch (error: Exception) {
        return "project root canonical path를 확인할 수 없습니다: $projectRoot"
    }
    if (canonicalRoot != projectRoot) {
        return "project root 경로에 symbolic link component가 있습니다: $projectRoot"
    }

    var current: Path = projectRoot
    for (component in listOf(".local", "keystore")) {
        current = current.resolve(component)
        if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
            continue
        }
        if (Files.isSymbolicLink(current)) {
            return "release keystore parent에 symbolic link가 있습니다: $current"
        }
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            return "release keystore parent가 directory가 아닙니다: $current"
        }
        val permissions = try {
            Files.getPosixFilePermissions(current, LinkOption.NOFOLLOW_LINKS)
        } catch (error: Exception) {
            return "release keystore parent mode를 확인할 수 없습니다: $current"
        }
        if (PosixFilePermission.GROUP_WRITE in permissions ||
            PosixFilePermission.OTHERS_WRITE in permissions
        ) {
            return "release keystore parent는 group 또는 other writable이면 안 됩니다: $current"
        }
        val canonicalCurrent = try {
            current.toRealPath()
        } catch (error: Exception) {
            return "release keystore parent canonical path를 확인할 수 없습니다: $current"
        }
        if (!canonicalCurrent.startsWith(canonicalRoot)) {
            return "release keystore parent가 project root 밖을 가리킵니다: $current"
        }
    }
    return null
}

android {
    namespace = "dev.sixbridge.manager"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sixbridge.manager"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    signingConfigs {
        create("managerRelease") {
            storeFile = releaseKeystore.asFile
            storeType = "PKCS12"
            keyAlias = releaseKeyAlias
            if (releaseStorePassword.isPresent && releaseKeyPassword.isPresent) {
                storePassword = releaseStorePassword.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes.named("release") {
        signingConfig = signingConfigs.getByName("managerRelease")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val verifyManagerReleaseSigningInput by tasks.registering {
    group = "verification"
    description = "Android manager release 서명 입력을 검증합니다."

    doLast {
        val errors = buildList {
            releaseKeystorePathIssue()?.let(::add)
            val keystorePath = releaseKeystore.asFile.toPath()
            if (!Files.isRegularFile(keystorePath, LinkOption.NOFOLLOW_LINKS)) {
                add("release keystore가 regular file이 아닙니다: ${releaseKeystore.asFile}")
            }

            val storePassword = releaseStorePassword.orNull
            val keyPassword = releaseKeyPassword.orNull
            if (storePassword.isNullOrEmpty()) {
                add("SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD가 설정되지 않았습니다.")
            }
            if (keyPassword.isNullOrEmpty()) {
                add("SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD가 설정되지 않았습니다.")
            }
            if (!storePassword.isNullOrEmpty() && storePassword.length < 16) {
                add("SIXBRIDGE_ANDROID_RELEASE_STORE_PASSWORD는 16자 이상이어야 합니다.")
            }
            if (!keyPassword.isNullOrEmpty() && keyPassword.length < 16) {
                add("SIXBRIDGE_ANDROID_RELEASE_KEY_PASSWORD는 16자 이상이어야 합니다.")
            }
            if (!storePassword.isNullOrEmpty() &&
                !keyPassword.isNullOrEmpty() &&
                storePassword != keyPassword
            ) {
                add("release store 및 key 비밀번호는 동일해야 합니다.")
            }
        }

        check(errors.isEmpty()) {
            "Android release 서명 입력 검증 실패:\n" +
                errors.joinToString(separator = "\n") { "- $it" }
        }
    }
}

tasks.configureEach {
    if (name == "packageRelease") {
        dependsOn(verifyManagerReleaseSigningInput)
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (providers.gradleProperty("sixbridge.skipSecureZipHostTests").orNull == "true") {
        exclude("dev/sixbridge/manager/conversion/OnDeviceInputServiceTest.class")
        exclude("dev/sixbridge/manager/conversion/DefaultConversionRuntimeTest.class")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

configurations.configureEach {
    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
}

dependencies {
    implementation(project(":bundle-contract"))
    implementation(project(":core"))
    implementation("com.android.tools.build:apksig:9.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2026.06.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    ksp("androidx.room:room-compiler:2.8.4")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.06.00"))
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
