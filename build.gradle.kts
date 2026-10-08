plugins {
    id("com.android.application") version "8.9.2" apply false
    id("com.android.library") version "8.9.2" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
tasks.register("verifyArchitecture") {
    doLast {
        val allowed = mapOf(
            ":app" to setOf(":core:domain", ":core:agent", ":data:local", ":integration:model", ":platform:android", ":feature:ui"),
            ":core:domain" to emptySet(), ":core:agent" to setOf(":core:domain"),
            ":data:local" to setOf(":core:domain"), ":integration:model" to setOf(":core:agent"),
            ":platform:android" to setOf(":core:domain"), ":feature:ui" to setOf(":core:domain", ":core:agent")
        )
        subprojects.forEach { p -> p.configurations.forEach { c ->
            c.dependencies.withType(org.gradle.api.artifacts.ProjectDependency::class.java).forEach { d ->
                check(d.dependencyProject.path == p.path || d.dependencyProject.path in allowed.getValue(p.path)) { "Forbidden dependency: ${p.path} -> ${d.dependencyProject.path}" }
            }
        } }
        listOf("core/domain", "core/agent").forEach { path ->
            fileTree("$path/src/main/kotlin").matching { include("**/*.kt") }.forEach { source ->
                check(!Regex("import (android\\.|androidx\\.|okhttp3\\.)").containsMatchIn(source.readText())) { "Platform import in ${source.name}" }
            }
        }
        println("Module dependencies and core imports verified.")
    }
}
