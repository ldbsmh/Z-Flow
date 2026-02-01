import com.android.build.api.dsl.ApplicationExtension

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.refine)
}

extensions.configure<ApplicationExtension> {
    namespace = "com.sunshine.freeform"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sunshine.freeform"
        minSdk = 34
        targetSdk = 36
        versionCode = 3000
        versionName = "3.0.00"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

//    signingConfigs {
//        create("release") {
//            storeFile = file("D:\\code\\Android\\AndroidStudioProjects\\sunshinekey.jks")
//            storePassword = "androidhas1"
//            keyAlias = "demo"
//            keyPassword = "androidhas1"
//        }
//    }

    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
//            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        viewBinding = true
    }
}

configurations.all {
    exclude(group = "androidx.appcompat", module = "appcompat")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    compileOnly(libs.xposed.api)

    implementation(libs.rikkax.appcompat)
    implementation(libs.rikkax.borderview)
    implementation(libs.rikkax.recyclerview.ktx)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)

    implementation(libs.androidx.preference.ktx)
    implementation(libs.rikkax.preference.simplemenu)

    implementation(libs.tinypinyin)

    implementation(libs.lottie)

    implementation(libs.glide)

    implementation(libs.hiddenapibypass)

    implementation(libs.rikkax.refine.runtime)

    compileOnly(project(":hidden-api"))
}
