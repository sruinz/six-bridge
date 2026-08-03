package dev.sixbridge.core.wrapper

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

data class WrapperBuildRequest(
    val extensionId: String,
    val extensionName: String,
    val crxBytes: ByteArray,
    val outputApk: Path,
)

data class WrapperBuildResult(
    val outputApk: Path,
    val packageName: String,
    val versionCode: Int,
    val signerSha256: String,
)

class WrapperApkBuilder(
    private val toolchain: AndroidToolchain,
    private val localRoot: Path,
) {
    fun build(request: WrapperBuildRequest): WrapperBuildResult {
        require(EXTENSION_ID.matches(request.extensionId)) {
            "올바르지 않은 Chrome 확장 ID입니다: ${request.extensionId}"
        }
        val packageName = "dev.sixbridge.ext.${request.extensionId}"
        val versionStore = VersionStore(localRoot.resolve("versions"))
        val versionReservation = versionStore.reserve(request.extensionId) {
            existingOutputVersion(request.outputApk)
        }
        val versionCode = versionReservation.value
        try {
            val signer = WrapperSigningStore(toolchain, localRoot.resolve("keystore"))
            signer.ensure()
            Files.createDirectories(request.outputApk.toAbsolutePath().parent)
            val work = Files.createTempDirectory("sixbridge-wrapper-")
            val manifest = work.resolve("AndroidManifest.xml")
            manifest.writeText(
                androidManifest(
                    packageName = packageName,
                    extensionId = request.extensionId,
                    extensionName = request.extensionName,
                    versionCode = versionCode,
                ),
            )
            val javaSourceRoot = work.resolve("src/dev/sixbridge/runtime").createDirectories()
            javaSourceRoot.resolve("SixDiscoveryActivity.java").writeText(
                DISCOVERY_ACTIVITY_SOURCE,
            )
            javaSourceRoot.resolve("ExtensionProvider.java").writeText(PROVIDER_SOURCE)
            val classes = work.resolve("classes").createDirectories()
            compileRuntime(javaSourceRoot, classes)
            val dex = work.resolve("dex").createDirectories()
            ProcessRunner.run(
                listOf(
                    toolchain.d8.toString(),
                    "--min-api",
                    "30",
                    "--output",
                    dex.toString(),
                ) + Files.walk(classes).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".class") }
                        .map(Path::toString)
                        .toList()
                },
            )

            val base = work.resolve("base.apk")
            ProcessRunner.run(
                listOf(
                    toolchain.aapt2.toString(),
                    "link",
                    "-o",
                    base.toString(),
                    "-I",
                    toolchain.androidJar.toString(),
                    "--manifest",
                    manifest.toString(),
                    "--min-sdk-version",
                    "30",
                    "--target-sdk-version",
                    "36",
                    "--version-code",
                    versionCode.toString(),
                    "--version-name",
                    "1.0.$versionCode",
                ),
            )
            val assembled = work.resolve("assembled.apk")
            assembleApk(
                baseApk = base,
                classesDex = dex.resolve("classes.dex"),
                crxBytes = request.crxBytes,
                output = assembled,
            )
            val aligned = work.resolve("aligned.apk")
            ProcessRunner.run(
                listOf(
                    toolchain.zipAlign.toString(),
                    "-f",
                    "4",
                    assembled.toString(),
                    aligned.toString(),
                ),
            )
            val signedApk = if (request.outputApk.fileSystem == FileSystems.getDefault()) {
                request.outputApk
            } else {
                work.resolve("signed.apk")
            }
            ProcessRunner.run(
                listOf(
                    toolchain.apkSigner.toString(),
                    "sign",
                    "--ks",
                    signer.keyStore.toString(),
                    "--ks-type",
                    "PKCS12",
                    "--ks-key-alias",
                    WrapperSigningStore.ALIAS,
                    "--ks-pass",
                    "file:${signer.passwordFile}",
                    "--out",
                    signedApk.toString(),
                    aligned.toString(),
                ),
            )
            val verify = ProcessRunner.run(
                listOf(
                    toolchain.apkSigner.toString(),
                    "verify",
                    "--verbose",
                    "--print-certs",
                    signedApk.toString(),
                ),
            )
            val signerSha = SIGNER_SHA_REGEX.find(verify.stdout)
                ?.groupValues
                ?.get(1)
                ?.replace(":", "")
                ?.lowercase()
                ?: sha256(signer.keyStore.readBytes())
            if (signedApk != request.outputApk) {
                publishSignedApk(signedApk, request.outputApk)
            }
            versionReservation.commit()
            return WrapperBuildResult(
                outputApk = request.outputApk,
                packageName = packageName,
                versionCode = versionCode,
                signerSha256 = signerSha,
            )
        } finally {
            versionReservation.close()
        }
    }

    private fun compileRuntime(sourceRoot: Path, classes: Path) {
        ProcessRunner.run(
            listOf(
                toolchain.javac.toString(),
                "--release",
                "8",
                "-classpath",
                toolchain.androidJar.toString(),
                "-d",
                classes.toString(),
                sourceRoot.resolve("SixDiscoveryActivity.java").toString(),
                sourceRoot.resolve("ExtensionProvider.java").toString(),
            ),
        )
    }

    private fun existingOutputVersion(outputApk: Path): Int {
        if (!outputApk.exists() || Files.size(outputApk) == 0L) return 0
        val inspectionApk = if (outputApk.fileSystem == FileSystems.getDefault()) {
            outputApk
        } else {
            Files.createTempFile("sixbridge-wrapper-inspection-", ".apk").also {
                Files.copy(outputApk, it, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return try {
            val badging = ProcessRunner.run(
                listOf(toolchain.aapt2.toString(), "dump", "badging", inspectionApk.toString()),
            ).stdout
            PACKAGE_BADGING.find(badging)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: throw IllegalArgumentException(
                    "기존 output APK versionCode를 읽을 수 없습니다: $outputApk",
                )
        } finally {
            if (inspectionApk != outputApk) Files.deleteIfExists(inspectionApk)
        }
    }

    private fun publishSignedApk(signedApk: Path, outputApk: Path) {
        val parent = requireNotNull(outputApk.toAbsolutePath().parent)
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".${outputApk.fileName}.", ".tmp")
        try {
            Files.copy(signedApk, temporary, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(
                temporary,
                outputApk,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun assembleApk(
        baseApk: Path,
        classesDex: Path,
        crxBytes: ByteArray,
        output: Path,
    ) {
        ZipOutputStream(Files.newOutputStream(output)).use { zipOut ->
            ZipInputStream(Files.newInputStream(baseApk)).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    val contents = zipIn.readBytes()
                    val copied = ZipEntry(entry.name).apply {
                        time = 0L
                        method = entry.method
                        if (method == ZipEntry.STORED) {
                            size = contents.size.toLong()
                            compressedSize = contents.size.toLong()
                            crc = CRC32().apply { update(contents) }.value
                        }
                    }
                    zipOut.putNextEntry(copied)
                    zipOut.write(contents)
                    zipOut.closeEntry()
                }
            }
            addEntry(zipOut, "classes.dex", classesDex.readBytes())
            addEntry(zipOut, "assets/extension.crx", crxBytes)
        }
    }

    private fun addEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun androidManifest(
        packageName: String,
        extensionId: String,
        extensionName: String,
        versionCode: Int,
    ): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="$packageName"
            android:versionCode="$versionCode"
            android:versionName="1.0.$versionCode">
            <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36" />
            <application
                android:allowBackup="false"
                android:hasCode="true"
                android:label="${xmlEscape(extensionName)}">
                <meta-data android:name="extension-id" android:value="$extensionId" />
                <activity
                    android:name="dev.sixbridge.runtime.SixDiscoveryActivity"
                    android:excludeFromRecents="true"
                    android:exported="true"
                    android:theme="@android:style/Theme.NoDisplay">
                    <intent-filter>
                        <action android:name="android.intent.action.VIEW" />
                        <category android:name="android.intent.category.DEFAULT" />
                        <category android:name="android.intent.category.BROWSABLE" />
                        <data android:scheme="six" android:host="ex" />
                    </intent-filter>
                </activity>
                <provider
                    android:name="dev.sixbridge.runtime.ExtensionProvider"
                    android:authorities="$packageName.contentprovider"
                    android:enabled="true"
                    android:exported="true"
                    android:grantUriPermissions="false" />
            </application>
        </manifest>
    """.trimIndent()

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val EXTENSION_ID = Regex("[a-p]{32}")
        private val SIGNER_SHA_REGEX =
            Regex("""Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)""")
        private val PACKAGE_BADGING =
            Regex("""package: name='[^']+' versionCode='(\d+)'""")

        private val DISCOVERY_ACTIVITY_SOURCE = """
            package dev.sixbridge.runtime;

            import android.app.Activity;
            import android.os.Bundle;

            public final class SixDiscoveryActivity extends Activity {
                @Override
                protected void onCreate(Bundle state) {
                    super.onCreate(state);
                    finish();
                }
            }
        """.trimIndent()

        private val PROVIDER_SOURCE = """
            package dev.sixbridge.runtime;

            import android.content.ContentProvider;
            import android.content.ContentValues;
            import android.database.Cursor;
            import android.net.Uri;
            import android.os.ParcelFileDescriptor;
            import java.io.File;
            import java.io.FileNotFoundException;
            import java.io.FileOutputStream;
            import java.io.IOException;
            import java.io.InputStream;
            import java.nio.file.Files;
            import java.nio.file.StandardCopyOption;

            public final class ExtensionProvider extends ContentProvider {
                @Override
                public boolean onCreate() {
                    return true;
                }

                @Override
                public ParcelFileDescriptor openFile(Uri uri, String mode)
                        throws FileNotFoundException {
                    if (!"r".equals(mode)) {
                        throw new FileNotFoundException("Read-only provider");
                    }
                    File target = new File(getContext().getFilesDir(), "extension.crx");
                    try {
                        copyAsset(target);
                    } catch (IOException error) {
                        throw new FileNotFoundException(error.getMessage());
                    }
                    return ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY);
                }

                private void copyAsset(File target) throws IOException {
                    File temporary = File.createTempFile("extension.crx.", ".tmp", target.getParentFile());
                    try {
                        try (InputStream input = getContext().getAssets().open("extension.crx");
                             FileOutputStream output = new FileOutputStream(temporary)) {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = input.read(buffer)) != -1) {
                                output.write(buffer, 0, read);
                            }
                            output.getFD().sync();
                        }
                        Files.move(
                                temporary.toPath(),
                                target.toPath(),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        Files.deleteIfExists(temporary.toPath());
                    }
                }

                @Override public String getType(Uri uri) {
                    return "application/x-chrome-extension";
                }
                @Override public Cursor query(
                        Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
                    return null;
                }
                @Override public Uri insert(Uri uri, ContentValues values) {
                    throw new UnsupportedOperationException("Read-only provider");
                }
                @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
                    throw new UnsupportedOperationException("Read-only provider");
                }
                @Override public int update(
                        Uri uri, ContentValues values, String selection, String[] selectionArgs) {
                    throw new UnsupportedOperationException("Read-only provider");
                }
            }
        """.trimIndent()
    }
}

private class VersionStore(
    private val directory: Path,
) {
    fun reserve(extensionId: String, existingOutputVersion: () -> Int): VersionReservation {
        directory.createDirectories()
        val file = directory.resolve("$extensionId.txt")
        val channel = FileChannel.open(
            directory.resolve("$extensionId.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        )
        val lock = try {
            channel.lock()
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
        return try {
            val storedVersion = if (file.exists()) {
                file.readText().trim().toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "versionCode 저장 파일이 손상되었습니다: $file",
                    )
            } else {
                0
            }
            val current = maxOf(storedVersion, existingOutputVersion())
            require(current >= 0) { "versionCode 저장 값은 음수일 수 없습니다." }
            require(current < Int.MAX_VALUE) { "versionCode가 최대값에 도달했습니다." }
            VersionReservation(file, current + 1, channel, lock)
        } catch (error: Throwable) {
            lock.release()
            channel.close()
            throw error
        }
    }
}

private class VersionReservation(
    private val file: Path,
    val value: Int,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    private var committed = false

    fun commit() {
        check(!committed) { "versionCode reservation은 한 번만 commit할 수 있습니다." }
        val temporary = Files.createTempFile(file.parent, ".${file.fileName}", ".tmp")
        try {
            Files.writeString(
                temporary,
                value.toString(),
                StandardOpenOption.TRUNCATE_EXISTING,
            )
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            committed = true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        if (lock.isValid) lock.release()
        if (channel.isOpen) channel.close()
    }
}

private class WrapperSigningStore(
    private val toolchain: AndroidToolchain,
    private val directory: Path,
) {
    val keyStore: Path = directory.resolve("wrapper.p12")
    val passwordFile: Path = directory.resolve("wrapper.pass")

    fun ensure() {
        directory.createDirectories()
        if (!passwordFile.exists()) {
            val password = ByteArray(32).also(SecureRandom()::nextBytes)
            createOwnerOnlyFile(passwordFile)
            Files.writeString(
                passwordFile,
                Base64.getUrlEncoder().withoutPadding().encodeToString(password),
                StandardCharsets.US_ASCII,
                StandardOpenOption.TRUNCATE_EXISTING,
            )
        }
        if (!keyStore.exists()) {
            val privateDirectory = Files.createTempDirectory(
                directory,
                ".wrapper-keystore-",
                PosixFilePermissions.asFileAttribute(OWNER_DIRECTORY),
            )
            val temporaryKeyStore = privateDirectory.resolve("wrapper.p12")
            try {
                ProcessRunner.run(
                    listOf(
                        toolchain.keytool.toString(),
                        "-genkeypair",
                        "-keystore",
                        temporaryKeyStore.toString(),
                        "-storetype",
                        "PKCS12",
                        "-storepass:file",
                        passwordFile.toString(),
                        "-keypass:file",
                        passwordFile.toString(),
                        "-alias",
                        ALIAS,
                        "-keyalg",
                        "RSA",
                        "-keysize",
                        "3072",
                        "-validity",
                        "3650",
                        "-dname",
                        "CN=SIX Bridge Development,O=SIX Bridge,C=KR",
                        "-noprompt",
                    ),
                )
                restrictOwner(temporaryKeyStore)
                Files.move(
                    temporaryKeyStore,
                    keyStore,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                restrictOwner(keyStore)
            } finally {
                Files.deleteIfExists(temporaryKeyStore)
                Files.deleteIfExists(privateDirectory)
            }
        }
        restrictOwner(passwordFile)
        restrictOwner(keyStore)
    }

    private fun createOwnerOnlyFile(path: Path) {
        require(Files.getFileStore(path.parent).supportsFileAttributeView("posix")) {
            "owner-only 권한을 적용할 수 없는 파일 시스템입니다: $path"
        }
        Files.createFile(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        restrictOwner(path)
    }

    private fun restrictOwner(path: Path) {
        require(Files.getFileStore(path).supportsFileAttributeView("posix")) {
            "owner-only 권한을 적용할 수 없는 파일 시스템입니다: $path"
        }
        Files.setPosixFilePermissions(path, OWNER_ONLY)
        require(Files.getPosixFilePermissions(path) == OWNER_ONLY) {
            "owner-only 권한 적용을 확인할 수 없습니다: $path"
        }
    }

    companion object {
        const val ALIAS = "sixbridge"
        private val OWNER_ONLY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        private val OWNER_DIRECTORY = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }
}
