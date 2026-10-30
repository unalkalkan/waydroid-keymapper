import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "games.forgemaster.keymapper.helper"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 26
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
            }
        }
        consumerProguardFiles("consumer-rules.pro")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
}

// Standalone dex jar of the helper daemon + CLI (and their runtime libraries), usable as the
// app_process classpath without installing the app: ./gradlew :helper:helperJar
val helperDexDeps: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
            org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
        )
    }
}

dependencies {
    helperDexDeps(project(":core"))
}

val helperClassesJar by tasks.registering(Zip::class) {
    dependsOn("compileReleaseKotlin")
    from(layout.buildDirectory.dir("tmp/kotlin-classes/release"))
    archiveFileName.set("helper-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("dist/tmp"))
}

val helperJar by tasks.registering(Exec::class) {
    dependsOn(helperClassesJar)
    val out = layout.buildDirectory.file("dist/keymapperd.jar")
    inputs.files(helperClassesJar, helperDexDeps)
    outputs.file(out)
    doFirst {
        val sdk = android.sdkDirectory
        val d8 = File(sdk, "build-tools/${android.buildToolsVersion}/d8")
        val androidJar = File(sdk, "platforms/${android.compileSdkVersion}/android.jar")
        commandLine(
            listOf(d8.path, "--release", "--min-api", "26", "--lib", androidJar.path, "--output", out.get().asFile.path) +
                helperClassesJar.get().archiveFile.get().asFile.path +
                helperDexDeps.files.map { it.path },
        )
    }
}

// keymapperd.jar + lib/<abi>/libkmnative.so in build/dist, for scripts/deploy.sh --standalone.
val helperDist by tasks.registering(Copy::class) {
    dependsOn(helperJar, "stripReleaseDebugSymbols")
    from(layout.buildDirectory.dir("intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib")) {
        into("lib")
    }
    into(layout.buildDirectory.dir("dist"))
}
