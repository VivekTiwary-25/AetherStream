plugins { application }

dependencies {
    implementation(project(":compiler"))
    implementation(libs.kafka.clients)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.minio)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback)
}

// Two entry points: coordinator and worker. `installDist` produces both start scripts.
application { mainClass = "io.aetherstream.engine.coordinator.CoordinatorMain" }

val workerStartScripts by tasks.registering(CreateStartScripts::class) {
    mainClass = "io.aetherstream.engine.worker.WorkerMain"
    applicationName = "worker"
    outputDir = layout.buildDirectory.dir("worker-scripts").get().asFile
    classpath = tasks.named<Jar>("jar").get().outputs.files + configurations.runtimeClasspath.get()
}
distributions { main { contents { from(workerStartScripts) { into("bin") } } } }
