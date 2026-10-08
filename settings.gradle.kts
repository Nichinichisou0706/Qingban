pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Qingban"
include(":app", ":core:domain", ":core:agent", ":data:local", ":integration:model", ":platform:android", ":feature:ui")
