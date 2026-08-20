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

// 화면 계층을 검증하려면 JavaFX 툴킷이 필요하다. 표시 장치 없이 띄운다.
tasks.withType<Test>().configureEach {
    systemProperty("glass.platform", "Monocle")
    systemProperty("monocle.platform", "Headless")
    systemProperty("prism.order", "sw")
    systemProperty("prism.text", "t2k")
    systemProperty("java.awt.headless", "true")
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
    implementation(project(":modules:kiwoom-adapter"))
    implementation(project(":modules:secret-store-api"))
    implementation(project(":modules:windows-secret-store"))

    testImplementation(testFixtures(project(":modules:application")))
    testImplementation(project(":modules:fake-adapters"))
    // 헤드리스로 JavaFX 툴킷을 띄운다. CI 는 ubuntu 와 windows 를 모두 돌리는데
    // xvfb 는 windows 러너에서 쓸 수 없다.
    testRuntimeOnly("org.testfx:openjfx-monocle:jdk-12.0.1+2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
