plugins { `java-library` }

dependencies {
    api(project(":modules:voice-input-api"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
