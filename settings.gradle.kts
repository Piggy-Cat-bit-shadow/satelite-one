pluginManagement {
    repositories {
        // Mirrors first: the upstream hosts are frequently unreachable /
        // handshake-flaky on the networks this project is built from. The
        // canonical repositories stay as fallback so nothing depends on a
        // mirror being complete.
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        google()
        mavenCentral()
    }
}
rootProject.name = "interstellar"
include(":app")
