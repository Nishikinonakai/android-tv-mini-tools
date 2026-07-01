plugins {
    id("com.android.application")
}

android {
    namespace = "com.zy.tvhome"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zy.tvhome"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "0.17"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("debug") { isMinifyEnabled = false }
    }
}
