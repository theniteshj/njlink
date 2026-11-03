// SPDX-License-Identifier: GPL-3.0-or-later
import java.io.File
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The version comes from the workspace (Cargo.toml), like the PC app's.
val appVersion: String = rootProject.file("../Cargo.toml").readLines()
    .first { it.startsWith("version = ") }
    .substringAfter('"').substringBefore('"')
/** 1.2.3 → 1002003: every release must have a larger code than the last. */
val appVersionCode: Int = appVersion.substringBefore('-').split('.').map(String::toInt)
    .let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }

android {
    namespace = "app.njlink.android"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.njlink"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersion
    }

    // Only the languages the app offers (res/xml/locales_config.xml) go into the APK: a
    // translation still in progress stays out, so a phone in that language never shows
    // it half done (docs/translating.md).
    val offeredLocales = Regex("android:name=\"([^\"]+)\"")
        .findAll(file("src/main/res/xml/locales_config.xml").readText())
        .map { match ->
            val parts = match.groupValues[1].split('-')
            if (parts.size == 1) parts[0] else "${parts[0]}-r${parts[1]}"
        }
        .toList()
    androidResources {
        localeFilters += offeredLocales
    }

    // Release builds are signed with the project's key when CI provides it
    // (NJLINK_KEYSTORE: a .jks file; NJLINK_KEYSTORE_PASSWORD).
    // Without it, a local release build is signed with the debug key so it can be
    // installed over a debug build for testing. Never publish such a build: the
    // release workflow refuses to run without the key and verifies the signature.
    val keystore = System.getenv("NJLINK_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("NJLINK_KEYSTORE_PASSWORD")
                keyAlias = "njlink"
                keyPassword = System.getenv("NJLINK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

}

composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose_stability.conf"))
}

// ---- The Rust core (njlink-ffi) ----
//
// Built with cargo-ndk for the ABIs in `njlink.abis` (default: devices
// and the x86_64 emulator), then UniFFI generates the Kotlin bindings from
// the built library. Both are registered as generated sources, so AGP runs
// them when needed. Rust is always built in release: debug builds of the
// core are large and slow.

/** Builds the Rust core into `<outputDir>/<abi>/libnjlink_ffi.so`. */
abstract class CargoNdkBuild : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:Input abstract val abis: ListProperty<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val crates: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val lockFile: RegularFileProperty
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Internal abstract val ndk: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        exec.exec {
            workingDir = workspace.get().asFile
            environment("ANDROID_NDK_HOME", ndk.get().asFile.absolutePath)
            commandLine(
                buildList {
                    addAll(listOf("cargo", "ndk", "--platform", "26", "-o", outputDir.get().asFile.path))
                    abis.get().forEach { addAll(listOf("-t", it)) }
                    addAll(listOf("build", "--profile", "android", "--locked", "-p", "njlink-ffi"))
                },
            )
        }
    }
}

/** Generates the Kotlin bindings from a built core library. */
abstract class UniffiBindgen : DefaultTask() {
    @get:Inject abstract val exec: ExecOperations
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val library: RegularFileProperty
    @get:Internal abstract val workspace: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val configFile = workspace.file("uniffi-global.toml").get().asFile
        val out = outputDir.get().asFile
        out.mkdirs()
        exec.exec {
            workingDir = workspace.get().asFile
            commandLine(
                buildList {
                    addAll(listOf("cargo", "run", "--locked", "-p", "njlink-ffi", "--features", "bindgen",
                        "--bin", "uniffi-bindgen", "--", "generate", "--library", library.get().asFile.path))
                    if (configFile.exists()) {
                        addAll(listOf("--config", configFile.path))
                    }
                    addAll(listOf("--language", "kotlin", "--no-format", "--out-dir", out.path))
                }
            )
        }

        // Guarantee that generated bindings are always placed in app.njlink.core package
        val allKtFiles = out.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        println("[UniffiBindgen] Generated Kotlin file count: ${allKtFiles.size}")
        if (allKtFiles.isEmpty()) {
            throw GradleException("UniFFI did not generate any Kotlin binding files in ${out.path}")
        }
        allKtFiles.forEach { file ->
            println("[UniffiBindgen] Processing generated file: ${file.path}")
            var content = file.readText()
            content = content.replaceFirst(Regex("""^package\s+[^\r\n]+""", RegexOption.MULTILINE), "package app.njlink.core")
            content = content.replace("uniffi.njlink_ffi", "app.njlink.core")

            // UniFFI's casing rules (via heck ToUpperCamelCase) convert 'NJLink' into 'NjLink'.
            // Map them to 'NJLink' to match the Android Kotlin codebase.
            content = content.replace("NjLinkNode", "NJLinkNode")
            content = content.replace("NjlinkNode", "NJLinkNode")
            content = content.replace("NjLinkException", "NJLinkException")
            content = content.replace("NjlinkException", "NJLinkException")
            content = content.replace("NjLinkError", "NJLinkError")
            content = content.replace("NjlinkError", "NJLinkError")

            content += """

// Compatibility typealiases for NjLink / NJLink
typealias NjLinkNode = NJLinkNode
typealias NjLinkException = NJLinkException
typealias NjlinkNode = NJLinkNode
typealias NjlinkException = NJLinkException
"""

            file.writeText(content)
            
            // Mirror directly into src/main/kotlin so Kotlin compiler unconditionally compiles it
            val srcDestFile = project.file("src/main/kotlin/app/njlink/core/${file.name}")
            srcDestFile.parentFile.mkdirs()
            srcDestFile.writeText(content)
            println("[UniffiBindgen] Wrote bindings to ${srcDestFile.path} (${srcDestFile.length()} bytes)")
        }
    }
}

// ---- Fonts ----
//
// The shared fonts in `assets/fonts` (with their licenses) are packaged as
// app assets under `fonts/`.

/** Copies the bundled fonts and their licenses into `<outputDir>/fonts`. */
abstract class BundleFonts : DefaultTask() {
    @get:Inject abstract val fs: FileSystemOperations
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val fonts: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        fs.sync {
            from(fonts) { include("*.ttf", "OFL-*.txt") }
            into(outputDir.dir("fonts"))
        }
    }
}

val workspaceDir: Directory = rootProject.layout.projectDirectory.dir("..")
val rustAbis: List<String> =
    (findProperty("njlink.abis") as String?)?.split(",")?.map(String::trim) ?: listOf("arm64-v8a", "x86_64")

androidComponents {
    onVariants { variant ->
        val suffix = variant.name.replaceFirstChar(Char::uppercase)
        val cargo = tasks.register<CargoNdkBuild>("cargoNdkBuild$suffix") {
            description = "Builds the Rust core for Android."
            abis.set(rustAbis)
            crates.set(workspaceDir.dir("core"))
            lockFile.set(workspaceDir.file("Cargo.lock"))
            workspace.set(workspaceDir)
            ndk.set(androidComponents.sdkComponents.ndkDirectory)
        }
        val bindgen = tasks.register<UniffiBindgen>("uniffiBindgen$suffix") {
            description = "Generates the Kotlin bindings for the Rust core."
            library.set(cargo.flatMap { it.outputDir.file("${rustAbis.first()}/libnjlink_ffi.so") })
            workspace.set(workspaceDir)
            outputDir.set(layout.buildDirectory.dir("generated/uniffi/$suffix"))
        }
        val fonts = tasks.register<BundleFonts>("bundleFonts$suffix") {
            description = "Packages the shared fonts as assets."
            this.fonts.set(workspaceDir.dir("assets/fonts"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargo, CargoNdkBuild::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(fonts, BundleFonts::outputDir)

        // Ensure Kotlin compilation for this variant always runs after UniFFI bindings have been generated into src/main/kotlin
        tasks.matching { it.name == "compile${suffix}Kotlin" }.configureEach {
            dependsOn(bindgen)
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.zxing.core)
    // Elevated: the phone's own wireless debugging (pairing and shell).
    implementation(libs.libadb.android)
    implementation(libs.conscrypt.android)
    // Or through Shizuku, when the user picks it (#110).
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    // UniFFI's Kotlin bindings call the core through JNA.
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
}
