pluginManagement {
    repositories {
        if (!System.getenv("OSU2PHIRA_LOCAL_MAVEN_REPO").isNullOrBlank()) {
            maven { url = uri(System.getenv("OSU2PHIRA_LOCAL_MAVEN_REPO")!!) }
        }
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!System.getenv("OSU2PHIRA_LOCAL_MAVEN_REPO").isNullOrBlank()) {
            maven { url = uri(System.getenv("OSU2PHIRA_LOCAL_MAVEN_REPO")!!) }
        }
        maven { url = uri("https://dl.google.com/dl/android/maven2") }
        mavenCentral()
    }
}

rootProject.name = "Osu2Phira"
include(":app")
