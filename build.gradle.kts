plugins {
    base
}

allprojects {
    group = "org.ossproject"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }
}
