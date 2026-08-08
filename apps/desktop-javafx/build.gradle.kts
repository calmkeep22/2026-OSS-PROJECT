plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

javafx {
    version = "17.0.12"
    modules = listOf("javafx.controls")
}

application {
    mainClass = "org.ossproject.desktop.DesktopApplication"
}

dependencies {
    implementation(project(":modules:finance-domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:mock-trading"))
    implementation(project(":modules:anomaly-detection"))
    implementation(project(":modules:accessibility"))
    implementation(project(":modules:sonification"))
    implementation(project(":modules:fake-adapters"))
}
