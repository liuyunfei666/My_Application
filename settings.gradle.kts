pluginManagement {
    repositories {
        // 国内镜像源（按优先级排序）
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") } // 腾讯云
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }                 // 华为云

    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {

        // 国内镜像源（按优先级排序）
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") } // 腾讯云
        maven { url = uri("https://repo.huaweicloud.com/repository/maven/") }                 // 华为云

    }
}

rootProject.name = "My Application"
include(":app")