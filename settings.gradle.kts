pluginManagement {
    repositories {
        // Resolve plugin dependencies directly, without the Plugin Portal's Central redirect.
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "aetherstream"

include("common", "compiler", "reference", "engine", "control-api", "simulator", "tests")
