pluginManagement {
    repositories {
        // 国内镜像优先(阿里云),避免 dl.google.com / repo.maven.apache.org TLS 被墙
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/central")
        maven(url = "https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 国内镜像优先(阿里云),jitpack 保持官方
        maven(url = "https://maven.aliyun.com/repository/google")
        maven(url = "https://maven.aliyun.com/repository/central")
        maven(url = "https://jitpack.io")
        google()
        mavenCentral()
    }
}
rootProject.name = "kin"
include(":app")
