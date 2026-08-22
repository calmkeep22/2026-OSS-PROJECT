plugins { `java-library` }

dependencies {
    api(project(":modules:ai-insight-api"))
    implementation(project(":modules:finance-domain"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}
