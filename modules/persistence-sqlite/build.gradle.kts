plugins { `java-library` }
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
dependencies {
    api(project(":modules:application"))
    api(project(":modules:finance-domain"))
    api(project(":modules:anomaly-detection"))

    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
