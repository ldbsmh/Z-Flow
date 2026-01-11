plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.freeform.hiddenapi"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
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

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    compileOnly(libs.androidx.annotation)
    annotationProcessor(libs.rikkax.refine.annotation.processor)
    compileOnly(libs.rikkax.refine.annotation)
}
