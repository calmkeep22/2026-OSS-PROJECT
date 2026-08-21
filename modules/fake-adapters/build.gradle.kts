plugins { `java-library` }
dependencies {
    api(project(":modules:application"))
    api(project(":modules:finance-domain"))
    testImplementation(testFixtures(project(":modules:application")))
}
