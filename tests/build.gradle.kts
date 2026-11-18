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

// Container-backed integration and benchmark tests run only through their dedicated tasks.
tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration", "benchmark") } }

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

val benchmarkTest by tasks.registering(Test::class) {
    description = "Measures the Testcontainers engine benchmark and recovery workload."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("benchmark") }
    forkEvery = 1
    maxHeapSize = "2g"
    systemProperty("aether.schema", rootProject.file("infra/db/schema.sql").absolutePath)
    val reportDir = rootProject.layout.buildDirectory.dir("reports/benchmark")
    systemProperty("aether.benchmark.reportDir", reportDir.get().asFile.absolutePath)
    outputs.dir(reportDir)
    outputs.upToDateWhen { false }
    shouldRunAfter(tasks.named("test"))
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
