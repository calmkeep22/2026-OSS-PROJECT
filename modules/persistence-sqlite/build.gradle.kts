plugins { `java-library` }
dependencies {
    api(project(":modules:application"))
    api(project(":modules:finance-domain"))
    api(project(":modules:anomaly-detection"))

    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

}
