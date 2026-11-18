plugins { java }

dependencies {
    testImplementation(project(":reference"))
    testImplementation(project(":engine"))
    testImplementation(project(":simulator"))
    testImplementation(libs.kafka.clients)
    testImplementation(libs.postgres)
    testImplementation(libs.minio)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.kafka)
    testImplementation(libs.testcontainers.postgresql)
    testRuntimeOnly(libs.logback)
}

// Unit `test` stays empty here; integration tests are tagged and run by `integrationTest`.
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }

val integrationTest by tasks.registering(Test::class) {
    description = "Runs Testcontainers integration and fault tests."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("integration") }
    // Classes share fixed topic/table names; each gets a fresh infrastructure singleton.
    forkEvery = 1
    systemProperty("aether.schema", rootProject.file("infra/db/schema.sql").absolutePath)
    shouldRunAfter(tasks.named("test"))
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
