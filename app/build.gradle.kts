import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val sherpaVersion = "2.0.0"
val sherpaAar = layout.buildDirectory.file("downloads/sherpa-onnx-$sherpaVersion.aar")

val downloadSherpa = tasks.register("downloadSherpa") {
    val dest = sherpaAar
    outputs.file(dest)
    doLast {
        val out = dest.get().asFile
        if (isValidAar(out)) return@doLast
        out.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
        downloadFile(url, out)
        if (!isValidAar(out)) {
            out.delete()
            error("sherpa-onnx AAR from $url was not a zip archive")
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadSherpa)
}

android {
    namespace = "com.glasses.hfptranscribe"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.glasses.hfptranscribe"
        minSdk = 31
        targetSdk = 37
        versionCode = providers.gradleProperty("appVersionCode").get().toInt()
        versionName = providers.gradleProperty("appVersionName").get()
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(files(sherpaAar).builtBy(downloadSherpa))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
    testImplementation(libs.junit)
}

private fun isValidAar(file: File): Boolean {
    if (!file.isFile || file.length() < 1_000_000L) return false
    file.inputStream().use { input ->
        val magic = ByteArray(2)
        return input.read(magic) == 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
    }
}

private fun downloadFile(url: String, dest: File) {
    var current = url
    for (hop in 0 until 8) {
        val connection = (URI(current).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "glasses-hfp-transcribe")
            setRequestProperty("Accept", "application/octet-stream")
            connectTimeout = 30_000
            readTimeout = 180_000
        }
        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
                ?: error("Redirect from $current had no Location header")
            connection.disconnect()
            current = if (location.startsWith("http")) location else URI(current).resolve(location).toString()
            continue
        }
        if (code !in 200..299) {
            connection.disconnect()
            error("Failed to download sherpa-onnx ($code) from $current")
        }
        val tmp = File(dest.parentFile, dest.name + ".part")
        connection.inputStream.use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        connection.disconnect()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        return
    }
    error("Too many redirects downloading sherpa-onnx")
}
