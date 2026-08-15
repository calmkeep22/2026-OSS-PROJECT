plugins { `java-library` }
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
dependencies {
    api(project(":modules:secret-store-api"))
    implementation(project(":modules:file-secret-store"))
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    testImplementation(testFixtures(project(":modules:secret-store-api")))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
