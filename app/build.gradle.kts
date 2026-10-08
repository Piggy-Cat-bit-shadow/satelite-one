import com.android.build.api.variant.FilterConfiguration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.ZipFile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// signing.properties (gitignored) — release builds fall back to debug signing
// when absent so the project still builds on fresh checkouts.
val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// version.properties — 全局唯一的版本定义处 (设置页与 CI 的 tag 校验都依赖它)
val versionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

// ---------------------------------------------------------------------------
// Core provenance
//
// This client ships exactly ONE core: Piggy-Cat-bit-shadow/sing-box. The
// libbox.aar in app/libs is produced from that fork, so its revision must be
// recorded at build time — "which core built this APK" is the first question
// in any bug report. CI exports CORE_COMMIT / CORE_BRANCH; a local build falls
// back to the sibling checkout at ../sing-box (i.e. /tmp/sb/sing-box during
// development), then to "unknown".
// ---------------------------------------------------------------------------
val coreRepo = "Piggy-Cat-bit-shadow/sing-box"
val coreBranch = System.getenv("CORE_BRANCH")?.takeIf { it.isNotBlank() } ?: "testing"

fun gitIn(dir: java.io.File, vararg args: String): String? = runCatching {
    val proc = ProcessBuilder(listOf("git", "-C", dir.absolutePath) + args)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    out.takeIf { proc.exitValue() == 0 && it.isNotBlank() && !it.startsWith("fatal") }
}.getOrNull()

val libboxAar = File(projectDir, "libs/libbox.aar")

/**
 * The core build writes libbox.provenance next to libbox.aar, recording the exact
 * source revision and version string it baked into the binary. This file is the
 * AUTHORITATIVE source for the app's core metadata.
 *
 * Why it exists: the app used to derive CORE_COMMIT from the sing-box checkout at
 * APK-build time, while constant.Version was baked in at libbox-build time. Those
 * are two different moments, so editing the core build tooling or advancing the
 * checkout in between made the APK claim a core revision it did not contain.
 * Reading the producer's own record removes that failure mode entirely.
 */
val libboxProvenance = File(projectDir, "libs/libbox.provenance")

val provenance: Map<String, String> =
    if (libboxProvenance.isFile) {
        libboxProvenance.readLines()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap()
    } else {
        emptyMap()
    }

/** First existing sing-box checkout — only used when no provenance file is present. */
val coreCheckout: java.io.File? =
    listOf(
        System.getenv("CORE_CHECKOUT"),
        rootProject.projectDir.parentFile?.resolve("sing-box")?.absolutePath,
        "/tmp/sb/sing-box",
    ).filterNotNull().map(::File).firstOrNull { it.isDirectory }

val coreCommitFull: String =
    provenance["commit"]?.takeIf { it.isNotBlank() && it != "unknown" }
        ?: System.getenv("CORE_COMMIT_FULL")?.takeIf { it.isNotBlank() }
        ?: coreCheckout?.let { gitIn(it, "rev-parse", "HEAD") }
        ?: "unknown"

val coreCommit: String =
    if (coreCommitFull != "unknown") coreCommitFull.take(12) else "unknown"

/** Product version string baked into constant.Version (may be a tag, not a SHA). */
val coreVersion: String =
    provenance["version"]?.takeIf { it.isNotBlank() } ?: "unknown"

if (libboxAar.exists() && provenance.isEmpty()) {
    logger.warn(
        "app/libs/libbox.provenance is missing: core metadata falls back to the local " +
            "checkout and may not match the packaged libbox.aar. Rebuild the core with " +
            "build_libbox (it writes the provenance file) or let CI provide CORE_COMMIT.",
    )
}

val coreCommitDate: String =
    System.getenv("CORE_COMMIT_DATE")?.takeIf { it.isNotBlank() }
        ?: coreCheckout?.let { gitIn(it, "log", "-1", "--format=%cI") }
        ?: "unknown"

val coreDescribe: String =
    System.getenv("CORE_DESCRIBE")?.takeIf { it.isNotBlank() }
        ?: coreCheckout?.let { gitIn(it, "describe", "--tags", "--always") }
        ?: "unknown"

val buildDate: String =
    System.getenv("BUILD_DATE")?.takeIf { it.isNotBlank() }
        ?: DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())

android {
    namespace = "com.interstellar.proxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.interstellar.proxy"
        minSdk = 24
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")

        // Surfaced on the dashboard core card and in Settings → About.
        buildConfigField("String", "CORE_REPO", "\"$coreRepo\"")
        buildConfigField("String", "CORE_BRANCH", "\"$coreBranch\"")
        buildConfigField("String", "CORE_COMMIT", "\"$coreCommit\"")
        buildConfigField("String", "CORE_COMMIT_FULL", "\"$coreCommitFull\"")
        buildConfigField("String", "CORE_COMMIT_DATE", "\"$coreCommitDate\"")
        buildConfigField("String", "CORE_DESCRIBE", "\"$coreDescribe\"")
        // The version string the core baked in (Libbox.version() must equal this).
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
        buildConfigField("Boolean", "CORE_PRESENT", libboxAar.exists().toString())
    }


    signingConfigs {
        create("release") {
            if (signingProps.getProperty("storeFile") != null) {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // debug builds get their own applicationId so 地心游记 (debug) and
        // 星际穿越 (release) coexist on the same device
        debug {
            applicationIdSuffix = ".debug"
            if (signingProps.getProperty("storeFile") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // per-ABI APKs instead of one huge universal blob.
    // arm64-v8a is the shipping target; x86_64 is kept for emulator debug runs.
    // armeabi-v7a / x86 were dropped: 32-bit devices are not a target for this
    // client and carrying two extra 70MB+ libbox variants only bloated CI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        aidl = true
        // 设置页"版本"行读取 BuildConfig.VERSION_NAME
        buildConfig = true
    }
}

// ---------------------------------------------------------------------------
// Core provenance gate
//
// Verifies that the libbox.aar being packaged really carries the revision and
// version we are about to advertise in BuildConfig. Reads the `-X
// github.com/sagernet/sing-box/constant.Version=<value>` record that Go embeds
// in the shared library — the same value Libbox.version() returns at runtime —
// so "About says aaaefb00" can never be true of an APK containing another core.
//
// A missing or unreadable AAR is only a warning: this project intentionally
// supports building without libbox.aar (the dependency is conditional), and the
// gate must not turn that into a hard error. A mismatch is always fatal.
// ---------------------------------------------------------------------------
val verifyCoreProvenance = tasks.register("verifyCoreProvenance") {
    group = "verification"
    description = "Assert the packaged libbox carries the advertised core revision/version"
    val aar = libboxAar
    val expectedCommit = coreCommitFull
    val expectedVersion = coreVersion
    inputs.file(aar).withPropertyName("libboxAar").optional()
    inputs.property("expectedCommit", expectedCommit)
    inputs.property("expectedVersion", expectedVersion)
    outputs.upToDateWhen { false }
    doLast {
        if (!aar.isFile) {
            logger.lifecycle(
                "verifyCoreProvenance: app/libs/libbox.aar absent — skipping " +
                    "(no core is packaged in this build)",
            )
            return@doLast
        }
        if (expectedCommit == "unknown" || expectedVersion == "unknown") {
            throw GradleException(
                "Core provenance is unknown but libbox.aar is packaged. Provide " +
                    "app/libs/libbox.provenance (written by build_libbox) or CORE_COMMIT.",
            )
        }

        // constant.Version is linked in as a plain NUL-terminated atom (the -X
        // symbol path itself does not survive into .rodata), so the honest check
        // is a byte search for the identity we advertise.
        val commit = expectedCommit.trim()
        val version = expectedVersion.trim()
        // CI builds the core with version == commit == resolved SHA, so this is
        // exact there. When they differ (e.g. a release tag plus a SHA), the
        // longest common string is what can be verified.
        // CI builds the core with version == commit. A tag-style version embeds
        // the revision (e.g. "v1.2.3-4-g<sha>"), in which case the SHA is the
        // verifiable part. Anything else cannot be checked against the binary.
        val candidate = when {
            commit == version -> commit
            version.contains(commit) -> commit
            else -> null
        }
        if (candidate == null || candidate.length < 7) {
            // Do not degrade to a warning: shipping an APK whose advertised core
            // identity cannot be checked against its own binary is exactly the
            // failure this gate exists to prevent.
            throw GradleException(
                """
                Core provenance is not verifiable — refusing to ship a mislabelled APK.
                  provenance version  : $version
                  provenance revision : $commit
                These share no comparable value, so the packaged binary cannot be
                checked against what this APK advertises. Build the core with
                SING_BOX_BUILD_VERSION=<resolved sha> so version and revision agree.
                """.trimIndent(),
            )
        }

        fun aarContains(needle: String): Boolean {
            val bytes = needle.toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty()) return false
            ZipFile(aar).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.name.endsWith("libbox.so")) continue
                    zip.getInputStream(entry).use { input ->
                        // streamed search: the shared objects are tens of MB each
                        val chunk = ByteArray(1 shl 20)
                        val overlap = ByteArray(maxOf(0, bytes.size - 1))
                        var overlapLen = 0
                        while (true) {
                            val n = input.read(chunk)
                            if (n < 0) break
                            val hay = ByteArray(overlapLen + n)
                            System.arraycopy(overlap, 0, hay, 0, overlapLen)
                            System.arraycopy(chunk, 0, hay, overlapLen, n)
                            if (indexOfBytes(hay, bytes) >= 0) return true
                            // carry the tail so a needle split across chunks is caught
                            overlapLen = minOf(overlap.size, hay.size)
                            if (overlapLen > 0) {
                                System.arraycopy(hay, hay.size - overlapLen, overlap, 0, overlapLen)
                            }
                        }
                    }
                }
            }
            return false
        }

        if (!aarContains(candidate)) {
            throw GradleException(
                """
                Core provenance mismatch — refusing to ship a mislabelled APK.
                  packaged libbox.aar does not contain : $candidate
                  BuildConfig CORE_COMMIT (provenance)  : $commit
                  BuildConfig CORE_VERSION (provenance) : $version
                The AAR was not built from the revision this APK advertises.
                Rebuild libbox.aar from the recorded revision (CI passes
                SING_BOX_BUILD_VERSION=<core sha>), or refresh the provenance file.
                """.trimIndent(),
            )
        }
        // When the version is a bare revision (CI sets version == commit), it must
        // appear verbatim as well — otherwise a correct revision paired with a
        // wrong version would still pass. Tag-style versions are not asserted here:
        // they are not required to be embedded for the artifact to be traceable,
        // and the revision check above already pins the binary.
        val versionIsBareSha = version.length in 7..64 &&
            version.all { it in "0123456789abcdefABCDEF" }
        if (versionIsBareSha && version != candidate && !aarContains(version)) {
            throw GradleException(
                """
                Core version mismatch — refusing to ship a mislabelled APK.
                  packaged libbox.aar does not contain : $version
                  BuildConfig CORE_VERSION (provenance) : $version
                The binary was not built with the version string this APK advertises.
                """.trimIndent(),
            )
        }

        logger.lifecycle(
            "verifyCoreProvenance: OK — packaged libbox carries the advertised " +
                "core identity '$candidate'" +
                (if (version != candidate) " with version '$version'" else ""),
        )
    }
}

tasks.matching { it.name == "assembleDebug" || it.name == "assembleRelease" }.configureEach {
    dependsOn(verifyCoreProvenance)
}


/** Index of [needle] in [hay] (Latin-1 byte compare), or -1. */
fun indexOfBytes(hay: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || hay.size < needle.size) return -1
    outer@ for (i in 0..(hay.size - needle.size)) {
        for (j in needle.indices) {
            if (hay[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}

// APK 输出统一以 satelite-one 开头：satelite-one-<abi>-<buildType>.apk
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }
                ?.identifier
            output.outputFileName.set("satelite-one-${abi ?: "universal"}-${variant.name}.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // sing-box core (built via `make lib_android` from the sing-box source)
    if (File(projectDir, "libs/libbox.aar").exists()) {
        implementation(files("libs/libbox.aar"))
    }

    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.charleskorn.kaml:kaml:0.104.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // real org.json on the test classpath: SmartSwitchEngine uses org.json, and
    // the SDK stub throws "not mocked" under plain JVM unit tests
    testImplementation("org.json:json:20240303")
}
