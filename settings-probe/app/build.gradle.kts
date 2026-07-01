plugins {
    id("com.android.application")
}

android {
    namespace = "com.zy.tvprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zy.tvprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }
}
