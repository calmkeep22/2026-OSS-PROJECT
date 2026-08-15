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
        "modules/file-secret-store" to listOf("javafx.", "com.sun.jna", "org.ossproject.secret.windows")
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
