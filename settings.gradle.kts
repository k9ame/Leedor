pluginManagement {
    repositories {
        // 腾讯 Maven 镜像：CI 里被证实可达（Gradle 发行包也从该域名下载），且包含 Central 上的插件标记
        maven { setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/jcenter") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 腾讯 Maven 镜像：CI 里被证实可达（Gradle 发行包也从该域名下载），且包含 Central 上的插件标记
        maven { setUrl("https://mirrors.cloud.tencent.com/nexus/repository/maven-public") }
        maven { setUrl("https://maven.aliyun.com/repository/central") }
        maven { setUrl("https://maven.aliyun.com/repository/jcenter") }
        maven { setUrl("https://maven.aliyun.com/repository/google") }
        maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "Leedor"
include(":app")
