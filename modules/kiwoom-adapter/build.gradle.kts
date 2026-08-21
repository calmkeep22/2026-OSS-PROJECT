plugins { `java-library` }
dependencies {
    api(project(":modules:broker-api"))
    api(project(":modules:secret-store-api"))
    api(project(":modules:application"))
    api(project(":modules:finance-domain"))

    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation(testFixtures(project(":modules:application")))
}
