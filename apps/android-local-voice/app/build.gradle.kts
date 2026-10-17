import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

val pocketAar = file("libs/onnx-irodori-isolated.aar")
val piperAar = file("libs/piper-plus-release.aar")
val sanoEvaluation = providers.gradleProperty("sanoEvaluation").orNull == "true"
val voicevoxEvaluation = providers.gradleProperty("voicevoxEvaluation").orNull == "true"

android {
    namespace = "jp.stackchan.localvoicepoc"
    compileSdk = 37
    // Use an already installed NDK for native symbol stripping, without SDK downloads.
    providers.gradleProperty("nativeNdkPath").orNull?.let { path ->
        ndkPath = path
        val installed = Properties().apply {
            file(path).resolve("source.properties").inputStream().use { load(it) }
        }
        ndkVersion = requireNotNull(installed.getProperty("Pkg.Revision")).trim()
    }

    defaultConfig {
        applicationId = "jp.stackchan.localvoicepoc"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.1.8-pocket-presets"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PIPER_PLUS_AAR_PRESENT", piperAar.exists().toString())
        buildConfigField("boolean", "POCKET_RUNTIME_PRESENT", (pocketAar.exists() && file("src/main/jniLibs/arm64-v8a/libirte_pocket.so").exists()).toString())
        buildConfigField("boolean", "PIPER_PLUS_2_AAR_PRESENT", file("libs/piper-plus-2-isolated.aar").exists().toString())

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    sourceSets {
        getByName("test") {
            resources.directories.add(rootProject.file("../../contracts/usb-cdc-v2").path)
        }
    }

    buildTypes {
        debug {
            if (sanoEvaluation) {
                applicationIdSuffix = ".sanoeval"
                versionNameSuffix = "-sano-eval"
                resValue("string", "app_name", "スタックちゃん")
            } else if (voicevoxEvaluation) {
                applicationIdSuffix = ".voicevoxeval"
                versionNameSuffix = "-voicevox-eval"
                resValue("string", "app_name", "StackChan VOICEVOX Eval")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // App-local canonical runtime is verified against all native imports and APK hash.
            pickFirsts += "lib/arm64-v8a/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += setOf(
                "/debian-*/**",
                "/macos-*/**",
                "/win-*/**",
            )
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(files(pocketAar))
    implementation(files("libs/piper-plus-2-isolated.aar"))
    implementation(files("libs/voicevoxcore-android-0.17.0.aar"))
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("jakarta.annotation:jakarta.annotation-api:2.1.1")
    implementation("jakarta.validation:jakarta.validation-api:3.0.2")
    if (piperAar.exists()) {
        implementation(files(piperAar))
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.runanywhere.sdk)
    implementation(libs.runanywhere.onnx)
    implementation(libs.runanywhere.llamacpp)
    implementation(libs.litert.lm)
    implementation(libs.webrtc.vad)
    implementation(libs.usb.serial)
    implementation(libs.compose.icons.lucide)
    implementation(libs.mcp.kotlin.client)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    jvmArgs("-XX:ActiveProcessorCount=1")
    providers.gradleProperty("sanoFixtureDir").orNull?.let {
        systemProperty("sano.fixture.dir", it)
    }
    providers.gradleProperty("sanoPcmFixture").orNull?.let {
        systemProperty("sano.pcm.fixture", it)
    }
    providers.gradleProperty("pocketFixtureRoot").orNull?.let { systemProperty("pocket.fixture.root", it) }
    providers.gradleProperty("pocketPcmFixture").orNull?.let { systemProperty("pocket.pcm.fixture", it) }
    providers.gradleProperty("piper2PcmFixture").orNull?.let {
        systemProperty("piper2.pcm.fixture", it)
    }
    providers.gradleProperty("voicevoxDictionaryFixture").orNull?.let {
        systemProperty("voicevox.dictionary.fixture", it)
    }
}
