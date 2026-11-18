plugins {
    java
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(project(":reference"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation(libs.kafka.clients)
    runtimeOnly(libs.postgres)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
