// Compile the Rust bridge before generating and compiling its Kotlin bindings.
import java.util.Locale
import java.util.Properties
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val ndkRevision = libs.versions.ndk.get()
val javaVersion = libs.versions.jdk.get().toInt()
val minimumSdk = libs.versions.minSdk.get().toInt()
val localProperties = Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.isFile) source.inputStream().use { load(it) }
}
val sdkPath = localProperties.getProperty("sdk.dir") ?: providers.environmentVariable("ANDROID_HOME").orNull
    ?: providers.environmentVariable("ANDROID_SDK_ROOT").orNull
    ?: error("Set sdk.dir in local.properties or ANDROID_HOME for this build")

abstract class RustlsVersion : ValueSource<String, RustlsVersion.Params> {
    interface Params : ValueSourceParameters {
        val lockFile: RegularFileProperty
    }

    companion object {
        const val CRATE_NAME = "rustls-platform-verifier-android"
    }

    override fun obtain(): String {
        val lines = parameters.lockFile.get().asFile.readLines()
        val nameIdx = lines.indexOfFirst { it.trim() == "name = \"$CRATE_NAME\"" }
        val version = if (nameIdx < 0) {
            null
        } else {
            lines.drop(nameIdx + 1).firstOrNull { it.trimStart().startsWith("version = ") }?.substringAfter('"')?.substringBefore('"')?.takeIf { it.isNotEmpty() }
        }
        return version ?: error("$CRATE_NAME not found in Cargo.lock")
    }
}

val rustlsPlatformVerifierVersion = providers.of(RustlsVersion::class.java) {
    parameters.lockFile.set(rootProject.layout.projectDirectory.file("Cargo.lock"))
}

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if ((requested.group == "rustls" || requested.group == "org.rustls") && requested.name == "rustls-platform-verifier") {
            useVersion(rustlsPlatformVerifierVersion.get())
            because("The Kotlin verifier must match rustls-platform-verifier-android in Cargo.lock")
        }
    }
}

val hostOs = System.getProperty("os.name").lowercase(Locale.ROOT)
val ndkHost = when {
    hostOs.startsWith("windows") -> "windows-x86_64"
    hostOs.startsWith("mac") -> "darwin-x86_64"
    hostOs.startsWith("linux") -> "linux-x86_64"
    else -> error("Unsupported NDK host: $hostOs")
}
val stripName = if (hostOs.startsWith("windows")) "llvm-strip.exe" else "llvm-strip"
val ndkDirectory = File(sdkPath, "ndk/$ndkRevision")
val ndkToolchain = File(ndkDirectory, "toolchains/llvm/prebuilt/$ndkHost")
val llvmStrip = File(ndkToolchain, "bin/$stripName")
// GUI-launched IDEs may not inherit the shell's Rust tool paths.
val configuredPath = localProperties.getProperty("rust.path")
    ?: providers.environmentVariable("PATH").getOrElse("")
val cargoHome = providers.environmentVariable("CARGO_HOME").orNull
    ?: File(System.getProperty("user.home"), ".cargo").absolutePath
val cargoBin = File(cargoHome, "bin")
val cargoName = if (hostOs.startsWith("windows")) "cargo.exe" else "cargo"
val cargoCandidates = configuredPath.split(File.pathSeparator).filter(String::isNotBlank)
    .map { File(it, cargoName) } + File(cargoBin, cargoName)
val cargoExecutable = localProperties.getProperty("rust.cargo")
    ?: cargoCandidates.firstOrNull { it.isFile }?.absolutePath
    ?: cargoName
val rustPath = (listOfNotNull(File(cargoExecutable).parent, cargoBin.absolutePath) + configuredPath)
    .distinct().joinToString(File.pathSeparator)
val ndkLibclang = listOf("bin", "lib", "lib64").map { File(ndkToolchain, it) }.firstOrNull { directory ->
    listOf("libclang.dll", "libclang.so", "libclang.dylib").any { File(directory, it).isFile }
}
val libclangPath = localProperties.getProperty("rust.libclang")
    ?: providers.environmentVariable("LIBCLANG_PATH").orNull
    ?: ndkLibclang?.absolutePath

abstract class StageNative : DefaultTask() {
    @get:InputFile
    abstract val source: RegularFileProperty

    @get:InputFile
    abstract val stripExecutable: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val abi = outputDirectory.get().asFile.resolve("arm64-v8a")
        abi.mkdirs()
        val library = abi.resolve("libfolia_bridge.so")
        source.get().asFile.copyTo(library, overwrite = true)
        // The cargo library stays unstripped so UniFFI can read its metadata. The packaged copy does not.
        val strip = ProcessBuilder(stripExecutable.get().asFile.absolutePath, "--strip-unneeded", library.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = strip.inputStream.bufferedReader().readText()
        val code = strip.waitFor()
        if (code != 0) {
            throw RuntimeException("llvm-strip exited $code: $output")
        }
    }
}

abstract class GenerateUniFfiBindings : Exec() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty
}

fun releaseProperty(name: String, alternate: String): String? {
    return localProperties.getProperty(name) ?: localProperties.getProperty(alternate)
}

android {
    namespace = "io.github.techuouo520.folia"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = ndkRevision
    buildToolsVersion = libs.versions.buildTools.get()
    defaultConfig {
        applicationId = "io.github.techuouo520.folia"
        minSdk = minimumSdk
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    val storePath = releaseProperty("RELEASE_STORE_FILE", "storeFile")
    val storePassword = releaseProperty("RELEASE_STORE_PASSWORD", "storePassword")
    val keyAliasName = releaseProperty("RELEASE_KEY_ALIAS", "keyAlias")
    val keyPasswordValue = releaseProperty("RELEASE_KEY_PASSWORD", "keyPassword")
    val releaseStore = storePath?.let { path ->
        val file = File(path)
        if (file.isAbsolute) file else rootProject.file(path)
    }
    signingConfigs {
        if (releaseStore != null && releaseStore.isFile && storePassword != null && keyAliasName != null && keyPasswordValue != null) {
            create("release") {
                storeFile = releaseStore
                this.storePassword = storePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    packaging {
        jniLibs {
            excludes += setOf("lib/x86/**", "lib/x86_64/**", "lib/armeabi-v7a/**", "lib/armeabi/**")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(javaVersion)
        targetCompatibility = JavaVersion.toVersion(javaVersion)
    }
}

androidComponents.onVariants { variant ->
    val release = variant.buildType == "release"
    val profile = if (release) "release" else "debug"
    val cap = variant.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    val rustSo = rootProject.layout.projectDirectory.file("target/aarch64-linux-android/$profile/libfolia_bridge.so")
    val buildRust = tasks.register<Exec>("buildRustBridge$cap") {
        group = "build"
        description = "Build the arm64 Android Rust bridge ($profile)"
        workingDir(rootProject.projectDir)
        environment("PATH", rustPath)
        environment("ANDROID_HOME", sdkPath)
        environment("ANDROID_NDK_HOME", ndkDirectory.absolutePath)
        libclangPath?.let { environment("LIBCLANG_PATH", it) }
        environment("CARGO_TARGET_DIR", rootProject.layout.projectDirectory.dir("target").asFile.absolutePath)
        val command = mutableListOf(cargoExecutable, "ndk", "-t", "arm64-v8a", "--platform", minimumSdk.toString(), "build", "--locked", "-p", "folia-bridge", "--lib")
        if (release) {
            command += "--release"
        }
        commandLine(command)
        outputs.file(rustSo)
        outputs.upToDateWhen { false }
    }
    val stageRust = tasks.register<StageNative>("stageRustBridge$cap") {
        dependsOn(buildRust)
        source.set(rustSo)
        stripExecutable.set(llvmStrip)
        outputDirectory.set(layout.buildDirectory.dir("rustJni/${variant.name}"))
    }
    val bindings = layout.buildDirectory.dir("generated/source/uniffi/${variant.name}/kotlin")
    val generateBindings = tasks.register<GenerateUniFfiBindings>("generateUniFfiBindings$cap") {
        group = "build"
        description = "Generate Kotlin bindings from the $profile Rust library"
        dependsOn(buildRust)
        workingDir(rootProject.projectDir)
        environment("PATH", rustPath)
        environment("CARGO_TARGET_DIR", rootProject.layout.projectDirectory.dir("target").asFile.absolutePath)
        commandLine(
            cargoExecutable, "run", "--locked", "-p", "folia-bindgen", "--",
            "generate", "--library", rustSo.asFile.absolutePath, "--language", "kotlin", "--out-dir", bindings.get().asFile.absolutePath, "--no-format",
        )
        inputs.file(rustSo)
        inputs.file(rootProject.file("crates/folia-bridge/uniffi.toml"))
        inputs.file(rootProject.file("Cargo.lock"))
        inputs.file(rootProject.file("crates/folia-bindgen/src/main.rs"))
        outputDirectory.set(bindings)
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(stageRust) { it.outputDirectory }
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateBindings) { it.outputDirectory }
}

kotlin { jvmToolchain(javaVersion) }

dependencies {
    // SalamPlayer's existing DP vendor-compatibility helper, staged locally (no repository fetch).
    implementation(files("libs/hiddenapibypass-6.1.aar"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
    implementation(libs.media3.session)
    implementation(libs.jna) { artifact { type = "aar" } }
    implementation(libs.rustls.platform.verifier)
}
