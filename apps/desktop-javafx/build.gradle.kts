plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

javafx {
    version = "17.0.12"
    modules = listOf("javafx.controls")
}

application {
    mainClass = "org.ossproject.desktop.DesktopApplication"
}

val desktopJava = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(17)
}

fun jpackageExecutable(): File {
    val javaExecutable = desktopJava.get().executablePath.asFile
    return javaExecutable.parentFile.resolve(if (System.getProperty("os.name").startsWith("Windows")) "jpackage.exe" else "jpackage")
}

tasks.register<Exec>("packagePortable") {
    group = "distribution"
    description = "JRE를 포함한 Windows 포터블 앱 이미지를 생성합니다."
    dependsOn(tasks.named("installDist"))
    doFirst {
        val destination = layout.buildDirectory.dir("package/portable").get().asFile
        project.delete(destination)
        commandLine(
            jpackageExecutable(),
            "--type", "app-image",
            "--name", "OpenStockAccess",
            "--app-version", "0.1.0",
            "--vendor", "OpenStock Access OSS",
            "--description", "시각장애인 접근성을 우선한 오픈소스 모의투자 데스크톱 앱",
            "--input", layout.buildDirectory.dir("install/desktop-javafx/lib").get().asFile,
            "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
            "--main-class", application.mainClass.get(),
            "--dest", destination
        )
    }
}

tasks.register<Exec>("packageWindowsInstaller") {
    group = "distribution"
    description = "WiX Toolset이 설치된 Windows에서 EXE 설치 프로그램을 생성합니다."
    dependsOn(tasks.named("installDist"))
    doFirst {
        val destination = layout.buildDirectory.dir("package/installer").get().asFile
        project.delete(destination)
        commandLine(
            jpackageExecutable(),
            "--type", "exe",
            "--name", "OpenStockAccess",
            "--app-version", "0.1.0",
            "--vendor", "OpenStock Access OSS",
            "--description", "시각장애인 접근성을 우선한 오픈소스 모의투자 데스크톱 앱",
            "--input", layout.buildDirectory.dir("install/desktop-javafx/lib").get().asFile,
            "--main-jar", tasks.named<Jar>("jar").get().archiveFileName.get(),
            "--main-class", application.mainClass.get(),
            "--dest", destination,
            "--win-menu", "--win-shortcut", "--win-dir-chooser"
        )
    }
}

dependencies {
    implementation(project(":modules:finance-domain"))
    implementation(project(":modules:application"))
    implementation(project(":modules:mock-trading"))
    implementation(project(":modules:anomaly-detection"))
    implementation(project(":modules:accessibility"))
    implementation(project(":modules:sonification"))
    implementation(project(":modules:sonification-java-sound"))
    implementation(project(":modules:fake-adapters"))
    implementation(project(":modules:kiwoom-adapter"))
    implementation(project(":modules:secret-store-api"))
    implementation(project(":modules:windows-secret-store"))

    testImplementation(testFixtures(project(":modules:application")))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
