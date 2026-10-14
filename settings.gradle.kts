pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral(); maven("https://maven.aliucord.com/releases") { content { includeGroup("com.aliucord"); includeGroup("com.aliucord.lsplant") } } }
}
rootProject.name = "AetherSandbox"
include(":app", ":domain", ":guest-api", ":fixture", ":sampleapp")
