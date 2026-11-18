plugins { `java-library` }

dependencies {
    api(project(":compiler"))
    implementation(libs.kafka.clients)
}
