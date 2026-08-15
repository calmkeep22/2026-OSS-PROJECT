plugins { `java-library` }
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
dependencies {
    api(project(":modules:broker-api"))
    api(project(":modules:application"))
    api(project(":modules:finance-domain"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(testFixtures(project(":modules:application")))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
