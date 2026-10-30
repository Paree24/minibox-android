plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val coreDir = rootProject.file(providers.gradleProperty("minibox.coreDir").get()).canonicalPath
val juceDir = rootProject.file(providers.gradleProperty("minibox.juceDir").get()).canonicalPath

android {
    namespace = "com.pastel.minibox"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.pastel.minibox"
        minSdk = 28            // AAudio MMAP (exclusive) is available from 8.1 / API 27
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DMINIBOX_CORE_DIR=$coreDir",
                    "-DMINIBOX_JUCE_DIR=$juceDir",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    buildTypes {
        debug {
            // the audio path is unusable unoptimised: debug builds still compile the native code with -O2
            externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=RelWithDebInfo" } }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")   // personal builds; a real key comes with releases
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    androidResources {
        noCompress += listOf("wav", "mbx-mono", "mbx-poly", "mbx-acid", "mbx-fm", "mbx-sampler", "mbx-drums", "mbx-drumsampler")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Factory content (presets, samples, MIDI maps) ships as assets under Factory/ and is unpacked on first run.
abstract class CopyFactory : DefaultTask() {
    @get:InputDirectory abstract val source: DirectoryProperty
    @get:OutputDirectory abstract val output: DirectoryProperty
    @get:Inject abstract val fs: FileSystemOperations

    @TaskAction fun run() {
        fs.sync {
            from(source) { exclude("**/__pycache__/**") }
            into(output.dir("Factory"))
        }
    }
}
val copyFactory = tasks.register<CopyFactory>("copyFactory") { source.set(file("$coreDir/Factory")) }
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(copyFactory, CopyFactory::output) }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.oboe)
    debugImplementation(libs.compose.ui.tooling)
}
