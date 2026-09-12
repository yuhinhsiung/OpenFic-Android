import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * The Android client ships the same React bundle the Electron shell uses, so the app
 * renders instantly and keeps working even when the backend does not serve the SPA.
 * `frontend/dist` is produced by `pnpm build` in the sibling `frontend` module and is
 * git-ignored; this task is what turns it into packaged assets.
 */
val frontendDist = rootProject.layout.projectDirectory.dir("../frontend/dist")
val packagedFrontendDir = layout.projectDirectory.dir("src/main/assets/www")

val copyFrontendDist by tasks.registering(Copy::class) {
    from(frontendDist)
    into(packagedFrontendDir)
    doFirst {
        val index = frontendDist.file("index.html").asFile
        if (!index.isFile) {
            throw GradleException(
                "Frontend bundle missing at ${index.absolutePath}. " +
                    "Run `pnpm install && pnpm build` in the frontend/ directory first.",
            )
        }
    }
}

android {
    namespace = "com.openfic.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openfic.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.11.1"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // Used to gate WebView remote debugging on debug builds only.
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

/** Keep generated assets out of VCS; `assembleDebug` regenerates them from frontend/dist. */
tasks.named("preBuild") {
    dependsOn(copyFrontendDist)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
