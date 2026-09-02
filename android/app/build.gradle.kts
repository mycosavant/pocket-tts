plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.pockettts.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.pockettts.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // The sherpa-onnx AAR ships native libraries for four ABIs and is ~49 MB.
        // Phones shipping today are all arm64; keep armeabi-v7a for older hardware.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Signed with the debug key so the shrunk build can actually be
            // installed and tested. R8 is the part of the build no test sees,
            // and it has already removed a JNI callback once; an artefact
            // nobody can sideload is an artefact nobody checks. This is not a
            // distribution key and this app is not distributed through a store.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            // Robolectric boots the real activities against real resources, so
            // a theme or manifest mistake fails the build instead of the phone.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.recyclerview)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)

    // The AAR is depended on directly rather than through the JitPack parent
    // POM, which also pulls sherpa-onnx-jvm and the desktop native-lib jars -
    // duplicating every class and adding x86/macOS/Windows binaries to the APK.
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx:${libs.versions.sherpaOnnx.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
}
