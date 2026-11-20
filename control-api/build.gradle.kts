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
    testImplementation(project(":engine"))
    // Native PostgreSQL exercises the production audit SQL without Docker.
    testImplementation("io.zonky.test:embedded-postgres:2.2.2")
    // Native KRaft testkit keeps the in-JVM broker on the application's Kafka version.
    testImplementation("org.apache.kafka:kafka-test-common-runtime:${libs.versions.kafka.get()}")
}

sourceSets.test {
    resources.srcDir(rootProject.file("infra/db"))
}
