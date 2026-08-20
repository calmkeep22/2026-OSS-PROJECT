plugins {
    `java-library`
    `java-test-fixtures`
}
dependencies {
    api(project(":modules:finance-domain"))

    testFixturesApi("org.junit.jupiter:junit-jupiter-api:5.11.4")

}
