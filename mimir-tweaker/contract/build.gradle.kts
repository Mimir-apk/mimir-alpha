plugins {
    id("com.android.library")
}

android {
    namespace = "com.gruning.mimir.tweaker.contract"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
    }

    buildFeatures {
        aidl = true
    }
}
