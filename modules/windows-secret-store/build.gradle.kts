plugins { `java-library` }
dependencies {
    api(project(":modules:secret-store-api"))
    implementation(project(":modules:file-secret-store"))
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    testImplementation(testFixtures(project(":modules:secret-store-api")))
}
