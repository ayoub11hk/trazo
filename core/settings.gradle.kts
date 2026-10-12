// Lets the pure-Kotlin core be built on its own (`gradle -p core test`) in
// environments without the Android SDK. In the full build this file is ignored.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "trazo-core"
