plugins { kotlin("jvm"); kotlin("plugin.serialization") }
kotlin { jvmToolchain(17) }
dependencies { api(project(":core:domain")); testImplementation("junit:junit:4.13.2") }
