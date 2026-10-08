plugins { kotlin("jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core:agent"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
tasks.register<JavaExec>("evaluateModel") {
    group="verification"
    description="Evaluate a configured real model using synthetic data only (up to 54 requests)."
    classpath=sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.ming.qingban.model.ModelEvaluationMainKt")
}
