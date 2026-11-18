plugins { application }

dependencies {
    implementation(project(":common"))
    implementation(libs.kafka.clients)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback)
}

application { mainClass = "io.aetherstream.simulator.SimulatorMain" }
