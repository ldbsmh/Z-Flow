pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/public")
        maven { url = uri("https://api.xposed.info") }
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Z-Flow"
include(":app")
include(":hidden-api")
