plugins { `java-library` }
dependencies {
    api(project(":modules:secret-store-api"))
    testImplementation(testFixtures(project(":modules:secret-store-api")))
}
