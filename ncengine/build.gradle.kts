// NativeComplements 小窗引擎移植模块。
//
// 该模块把上游 NativeComplements 的小窗内核（WindowEngine / SystemUiHost /
// WindowControls / Wire / Config 等）以 library 形式并入 Z-Flow：
//  - 保留上游包名 dev.local.sunwindowdemo 与资源命名空间，避免大规模重命名；
//  - 由 Z-Flow 侧负责入口（侧边栏、磁贴、通知、桌面长按）与设置界面；
//  - Hook 框架保持上游的 libxposed API 101，与 Z-Flow 原有的经典 Xposed 入口并存。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.local.sunwindowdemo"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        // 上游用 BuildConfig.APPLICATION_ID 拼 authority/广播 action，
        // library 没有 applicationId，这里显式给出宿主包名。
        buildConfigField("String", "MODULE_PACKAGE", "\"io.relimus.zflow\"")
        buildConfigField("String", "MODULE_VERSION_NAME", "\"" + (providers.gradleProperty("zflowVersionName").orNull ?: "1.0.1") + "\"")
        buildConfigField("int", "MODULE_VERSION_CODE", (providers.gradleProperty("zflowVersionCode").orNull ?: "1"))
        // 引擎大量使用反射/字符串查找，混淆规则随库合并到宿主
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    // 悬浮菜单列表需要 NestedScrollingChild 兼容实现。
    implementation(libs.androidx.core.ktx)
    // libxposed API 101 由 LSPosed 在运行时注入，仅编译期可见。
    compileOnly(files("libs/libxposed-api-101.0.1.jar"))
    // system_server 内部类（SurfaceControl/WindowState 等）的编译桩，不打包。
    compileOnly(files("libs/hidden-stubs.jar"))
}
