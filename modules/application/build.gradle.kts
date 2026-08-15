plugins {
    `java-library`
    `java-test-fixtures`
}
java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
dependencies {
    api(project(":modules:finance-domain"))

    testFixturesApi("org.junit.jupiter:junit-jupiter-api:5.11.4")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
