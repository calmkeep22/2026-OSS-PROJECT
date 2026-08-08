plugins { `java-library` }
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
dependencies {
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
