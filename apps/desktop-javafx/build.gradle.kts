plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
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

val aiServiceDirectory = rootProject.layout.projectDirectory.dir("ai-service")
val aiBundleRoot = layout.buildDirectory.dir("ai-runtime")
val aiVenvPython = aiBundleRoot.map { it.file("venv/Scripts/python.exe") }
val aiExecutableDirectory = aiBundleRoot.map { it.dir("dist/OpenStockAiService") }

val createAiBundleVenv = tasks.register<Exec>("createAiBundleVenv") {
    group = "distribution"
    description = "AI 서버 패키징 전용 Python 가상환경을 만듭니다."
    inputs.file(aiServiceDirectory.file("requirements.txt"))
    outputs.file(aiVenvPython)
    commandLine("python", "-m", "venv", aiBundleRoot.get().dir("venv").asFile)
}

val installAiBundleDependencies = tasks.register<Exec>("installAiBundleDependencies") {
    group = "distribution"
    description = "AI 서버 실행 의존성과 PyInstaller를 전용 환경에 설치합니다."
    dependsOn(createAiBundleVenv)
    inputs.file(aiServiceDirectory.file("requirements.txt"))
    outputs.file(aiBundleRoot.map { it.file("venv/.dependencies-installed") })
    environment("PYTHONUTF8", "1")
    commandLine(aiVenvPython.get().asFile, "-m", "pip", "install", "--disable-pip-version-check",
        "-r", aiServiceDirectory.file("requirements.txt").asFile,
        "pyinstaller==6.16.0", "pyinstaller-hooks-contrib==2025.8")
    doLast {
        aiBundleRoot.get().file("venv/.dependencies-installed").asFile.writeText("installed")
    }
}

val bundleAiService = tasks.register<Exec>("bundleAiService") {
    group = "distribution"
    description = "Python 설치 없이 실행되는 AI 서버 EXE를 만듭니다."
    dependsOn(installAiBundleDependencies)
    workingDir(aiServiceDirectory)
    inputs.files(fileTree(aiServiceDirectory) {
        include("*.py", "accessible_investor/**/*.py", "models/**")
    })
    outputs.dir(aiExecutableDirectory)
    environment("PYTHONUTF8", "1")
    doFirst { delete(aiBundleRoot.get().dir("dist"), aiBundleRoot.get().dir("work")) }
    commandLine(
        aiVenvPython.get().asFile, "-m", "PyInstaller", "--noconfirm", "--clean", "--onedir",
        "--name", "OpenStockAiService",
        "--distpath", aiBundleRoot.get().dir("dist").asFile,
        "--workpath", aiBundleRoot.get().dir("work").asFile,
        "--specpath", aiBundleRoot.get().asFile,
        "--add-data", "${aiServiceDirectory.dir("models").asFile};models",
        "--collect-all", "uvicorn",
        "--exclude-module", "pytest",
        "--exclude-module", "matplotlib",
        "--exclude-module", "accessible_investor.cli",
        "--exclude-module", "accessible_investor.news_judge",
        "--exclude-module", "accessible_investor.news_predict",
        "--exclude-module", "accessible_investor.pairs",
        "--exclude-module", "accessible_investor.report",
        "--exclude-module", "accessible_investor.report_ai",
        "--exclude-module", "accessible_investor.results",
        "--exclude-module", "accessible_investor.reversion",
        "--exclude-module", "accessible_investor.segments",
        "--exclude-module", "accessible_investor.tabpfn_bench",
        "--exclude-module", "accessible_investor.training",
        "--exclude-module", "accessible_investor.viz",
        aiServiceDirectory.file("server.py").asFile
    )
}

fun copyAiBundleIntoApplication() {
    val target = layout.buildDirectory.dir("install/desktop-javafx/lib/ai-service").get().asFile
    delete(target)
    copy {
        from(aiExecutableDirectory)
        into(target)
    }
    copy {
        from(rootProject.layout.projectDirectory.file("LICENSE"))
        from(aiServiceDirectory.file("NOTICE.md"))
        into(File(target, "legal"))
    }
}

fun jpackageExecutable(): File {
    val javaExecutable = desktopJava.get().executablePath.asFile
    return javaExecutable.parentFile.resolve(if (System.getProperty("os.name").startsWith("Windows")) "jpackage.exe" else "jpackage")
}

tasks.register<Exec>("packagePortable") {
    group = "distribution"
    description = "JRE를 포함한 Windows 포터블 앱 이미지를 생성합니다."
    dependsOn(tasks.named("installDist"), bundleAiService)
    doFirst {
        copyAiBundleIntoApplication()
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
    dependsOn(tasks.named("installDist"), bundleAiService)
    doFirst {
        copyAiBundleIntoApplication()
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
    implementation(project(":modules:ai-insight-api"))
    implementation(project(":modules:ai-insight-http"))
    implementation(project(":modules:persistence-sqlite"))
    implementation(project(":modules:secret-store-api"))
    implementation(project(":modules:windows-secret-store"))
    implementation(project(":modules:voice-input-api"))
    implementation(project(":modules:voice-input-java-sound"))
    implementation(project(":modules:voice-input-http"))

    testImplementation(testFixtures(project(":modules:application")))
    testImplementation(project(":modules:fake-adapters"))
    // 헤드리스로 JavaFX 툴킷을 띄운다. CI 는 ubuntu 와 windows 를 모두 돌리는데
    // xvfb 는 windows 러너에서 쓸 수 없다.
    //
    // JavaFX 판과 같은 17 계열을 쓴다. jdk-12 판은 창을 띄우는 순간
    // MonocleWindow._updateViewSize 가 없어 AbstractMethodError 로 죽는다.
    testRuntimeOnly("org.testfx:openjfx-monocle:17.0.10")
}
