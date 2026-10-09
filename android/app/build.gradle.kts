plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "fr.louisraille.coucou"
    compileSdk = 36

    defaultConfig {
        applicationId = "fr.louisraille.coucou"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // The notification sounds are the Mac app's own files: one copy in the repo.
    // Filled by the mochiSounds task below, before anything is built.
    sourceSets["main"].res.srcDir("build/mochi-res")

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    // Jetpack Compose is to this app what SwiftUI is to the iPhone's.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    // The counterparts of the iPhone app's SF Symbols (Sym.kt). Not in the BOM any more.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Mochi's sounds for the notifications, taken from the Mac app's resources.
val mochiSounds = tasks.register<Sync>("mochiSounds") {
    from("../../NotchBuddy/Resources/sounds") {
        include("approval.wav", "question.wav", "finish.wav", "error.wav")
    }
    into(layout.buildDirectory.dir("mochi-res/raw"))
}
tasks.named("preBuild") { dependsOn(mochiSounds) }
