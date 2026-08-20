plugins { `java-library` }

dependencies {
    api(project(":modules:sonification"))

    testImplementation(testFixtures(project(":modules:sonification")))
}
