import com.android.build.api.dsl.ApplicationExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.refine)
}

// ===== 签名配置 =====
// 本地构建：读取根目录 keystore.properties（含密码，已在 .gitignore 中，不入库）。
// CI 构建：keystore 由 SIGNING_KEYSTORE_BASE64 Secret 解码到 SIGNING_STORE_FILE，
//          密码/alias 由 SIGNING_STORE_PASSWORD / SIGNING_KEY_PASSWORD / SIGNING_KEY_ALIAS 注入。
// keystore 文件本身不入库，避免仓库泄露即密钥泄露。
val signingProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    keystorePropertiesFile.inputStream().use { signingProperties.load(it) }
}

val signingStoreFilePath = System.getenv("SIGNING_STORE_FILE")
    ?: signingProperties.getProperty("storeFile")
val signingStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    ?: signingProperties.getProperty("storePassword")
val signingKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    ?: signingProperties.getProperty("keyPassword")
val signingKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    ?: signingProperties.getProperty("keyAlias")

// keystore 文件存在且密码齐全才启用签名；否则 release 产未签名 APK（仍可正常编译）
val signingStoreFileResolved = signingStoreFilePath?.let { rootProject.file(it) }
val hasSigningCreds = signingStoreFileResolved != null &&
    signingStoreFileResolved.exists() &&
    !signingStorePassword.isNullOrEmpty() &&
    !signingKeyPassword.isNullOrEmpty() &&
    !signingKeyAlias.isNullOrEmpty()

extensions.configure<ApplicationExtension> {
    namespace = "io.relimus.zflow"
    compileSdk = 36

    if (hasSigningCreds) {
        signingConfigs {
            create("release") {
                storeFile = signingStoreFileResolved
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "io.relimus.zflow"
        minSdk = 33
        targetSdk = 36
        // CI 通过 -PzflowVersionCode / -PzflowVersionName 注入构建序号；
        // 本地构建（未传参）时使用下面的默认值。
        versionCode = (providers.gradleProperty("zflowVersionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("zflowVersionName").orNull ?: "1.0.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 密码齐全时用 release 签名；否则产未签名 APK
            if (hasSigningCreds) {
                signingConfig = signingConfigs.getByName("release")
            }
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
    implementation(libs.ezxhelper.core)
    implementation(libs.ezxhelper.api)

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

    // Rikka Hidden API compat for system_server hooks
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.rikka.hidden.compat)

    compileOnly(project(":hidden-api"))
}
