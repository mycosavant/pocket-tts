pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // sherpa-onnx publishes its Android AAR through JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PocketTTS"
include(":app")
