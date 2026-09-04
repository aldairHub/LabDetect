import java.io.File
import java.util.Properties
import org.gradle.api.tasks.compile.JavaCompile

plugins {
    alias(libs.plugins.androidApplication)
}

fun String.forBuildConfig(): String = replace("\\", "\\\\").replace("\"", "\\\"")

fun readEnvFileValue(file: File, name: String): String = file
    .takeIf { it.isFile }
    ?.readLines()
    ?.firstOrNull { line -> line.trim().startsWith("$name=") }
    ?.substringAfter('=')
    ?.trim()
    ?.trim('"', '\'')
    .orEmpty()

android {
    namespace = "com.example.labdetect"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.labdetect"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val localProperties = Properties()
        rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use {
            localProperties.load(it)
        }
        // Para el prototipo, la APK llama directamente a OpenAI. local.properties y
        // backend/.env están ignorados por Git; la clave nunca se versiona.
        val openAiApiKey = providers.gradleProperty("OPENAI_API_KEY").orNull
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: localProperties.getProperty("OPENAI_API_KEY")?.takeIf { it.isNotBlank() }
            ?: readEnvFileValue(rootProject.file("backend/.env"), "OPENAI_API_KEY")
        val openAiModel = localProperties.getProperty("OPENAI_MODEL", "gpt-5.4-mini")
        val openAiTtsModel = localProperties.getProperty("OPENAI_TTS_MODEL", "gpt-4o-mini-tts")

        buildConfigField("String", "OPENAI_API_KEY", "\"${openAiApiKey.forBuildConfig()}\"")
        buildConfigField("String", "OPENAI_MODEL", "\"${openAiModel.forBuildConfig()}\"")
        buildConfigField("String", "OPENAI_TTS_MODEL", "\"${openAiTtsModel.forBuildConfig()}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // El modelo ONNX anterior se conserva en el historial del repositorio, pero no se
    // empaqueta: la APK usa únicamente el detector YOLO11s en TensorFlow Lite.
    androidResources {
        ignoreAssetsPattern = "labdetect_yolo26n.onnx:labdetect_yolo26n.metadata.json"
    }
}

// Mantiene javac dentro del proceso de Gradle; también evita procesos extra en CI.
tasks.withType<JavaCompile>().configureEach {
    options.isFork = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    // CameraX
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Detector YOLO local. El modelo TFLite se ejecuta completamente en el teléfono.
    implementation(libs.tensorflow.lite)
}
