import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aiclipstudio"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aiclipstudio"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.mediapipe:tasks-audio:1.2.2")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
}

val yamnetFile = file("src/main/assets/yamnet.tflite")
val yamnetHash = "4d8b4a53282dc83ef04e3e7dbc4fbc98082e34e44ed798e16c3a0cdd4c584faf"
val downloadYamnet = tasks.register("downloadYamnet") {
    outputs.file(yamnetFile)
    doLast {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val data = ByteArray(8192)
                while (true) {
                    val n = input.read(data)
                    if (n < 0) break
                    md.update(data, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (yamnetFile.exists() && sha256(yamnetFile) == yamnetHash) return@doLast
        yamnetFile.parentFile.mkdirs()
        val tmp = file(yamnetFile.absolutePath + ".tmp")
        try {
            val url = "https://huggingface.co/litert-community/MediaPipe-AudioClassifier-YAMNet/resolve/main/yamnet.tflite"
            URI(url).toURL().openStream().use { stream ->
                Files.copy(stream, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            check(sha256(tmp) == yamnetHash) { "YAMNet file checksum mismatch" }
            Files.move(tmp.toPath(), yamnetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
    }
}
tasks.named("preBuild") { dependsOn(downloadYamnet) }
