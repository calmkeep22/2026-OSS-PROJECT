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
    // 모든 모듈이 같은 Java 판과 같은 테스트 도구를 쓴다. 모듈마다 적어 두면 올릴 때
    // 열네 곳을 고쳐야 하고, 새 모듈을 만들 때마다 같은 덩어리를 옮겨 적게 된다.
    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(17)
            }
        }

        dependencies {
            add("testImplementation", platform("org.junit:junit-bom:5.11.4"))
            add("testImplementation", "org.junit.jupiter:junit-jupiter")
            add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("file.encoding", "UTF-8")
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).apply {
            charSet = "UTF-8"
            docEncoding = "UTF-8"
        }
    }

    pluginManager.withPlugin("java-library") {
        apply(plugin = "maven-publish")

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
            withJavadocJar()
        }

        extensions.configure<PublishingExtension> {
            publications {
                register<MavenPublication>("mavenJava") {
                    from(components["java"])
                }
            }
            repositories {
                maven {
                    name = "localBuild"
                    url = layout.buildDirectory.dir("repository").get().asFile.toURI()
                }
            }
        }
    }
}

val verifyModuleBoundaries by tasks.registering {
    group = "verification"
    description = "Checks that core modules do not import UI or infrastructure packages."

    val rules = mapOf(
        "modules/finance-domain" to listOf("javafx.", "org.ossproject.application", "org.ossproject.desktop", "org.ossproject.kiwoom"),
        "modules/application" to listOf("javafx.", "org.ossproject.desktop", "org.ossproject.kiwoom", "org.ossproject.mocktrading", "org.sqlite", "com.sun.jna"),
        "modules/broker-api" to listOf("javafx.", "org.ossproject.application", "org.ossproject.desktop", "org.ossproject.kiwoom"),
        "modules/sonification" to listOf(
            "javafx.",
            "javax.sound.sampled",
            "org.ossproject.application",
            "org.ossproject.desktop",
            "org.ossproject.finance",
            "org.ossproject.sonification.javasound"
        ),
        "modules/sonification-java-sound" to listOf(
            "javafx.",
            "org.ossproject.application",
            "org.ossproject.desktop",
            "org.ossproject.finance"
        ),
        "modules/accessibility" to listOf("javafx.", "org.ossproject.application", "org.ossproject.desktop", "org.ossproject.finance"),
        "modules/secret-store-api" to listOf("javafx.", "com.sun.jna", "org.ossproject.secret.file"),
        "modules/file-secret-store" to listOf("javafx.", "com.sun.jna", "org.ossproject.secret.windows"),
        "modules/windows-secret-store" to listOf("javafx.", "org.ossproject.application", "org.ossproject.desktop", "org.ossproject.finance"),

        // 분석 모듈은 도메인만 본다. 어디서 값을 가져왔는지 알 필요가 없다.
        "modules/anomaly-detection" to listOf(
            "javafx.", "org.ossproject.application", "org.ossproject.desktop",
            "org.ossproject.kiwoom", "org.ossproject.mocktrading", "org.sqlite"
        ),

        // 어댑터끼리는 서로를 모른다. 하나를 갈아 끼워도 나머지가 흔들리지 않아야 한다.
        "modules/kiwoom-adapter" to listOf(
            "javafx.", "org.ossproject.desktop", "org.ossproject.mocktrading",
            "org.ossproject.fake", "org.ossproject.persistence", "org.sqlite"
        ),
        "modules/mock-trading" to listOf(
            "javafx.", "org.ossproject.desktop", "org.ossproject.kiwoom",
            "org.ossproject.fake", "org.ossproject.persistence", "org.sqlite"
        ),
        "modules/persistence-sqlite" to listOf(
            "javafx.", "org.ossproject.desktop", "org.ossproject.kiwoom",
            "org.ossproject.mocktrading", "org.ossproject.fake"
        ),

        // 가짜 어댑터는 화면 개발과 테스트용이다. 실제 증권사 코드를 끌어오면 안 된다.
        "modules/fake-adapters" to listOf(
            "javafx.", "org.ossproject.desktop", "org.ossproject.kiwoom",
            "org.ossproject.mocktrading", "org.sqlite"
        ),

        // 앱은 조립 루트다. 어댑터를 고르는 것은 맞지만 저수준 라이브러리를 직접 쓰면
        // 그 결정이 모듈 밖으로 새어 나온다.
        "apps/desktop-javafx" to listOf("org.sqlite", "com.sun.jna", "java.net.http")
    )

    inputs.files(rules.keys.map { fileTree("$it/src/main/java") { include("**/*.java") } })
    doLast {
        val violations = mutableListOf<String>()
        rules.forEach { (module, forbidden) ->
            fileTree("$module/src/main/java") { include("**/*.java") }.forEach { source ->
                val text = source.readText(Charsets.UTF_8)
                forbidden.filter(text::contains).forEach { dependency ->
                    violations += "${source.relativeTo(rootDir)} imports forbidden boundary $dependency"
                }
            }
        }
        check(violations.isEmpty()) {
            "Module boundary violations:\n" + violations.joinToString("\n")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyModuleBoundaries)
}
