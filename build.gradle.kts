plugins {
    id("java")
    id("java-gradle-plugin")
    id("maven-publish")
    id("com.diffplug.spotless") version "7.0.3"
}

version = findProperty("version")!!
group = "de.rhm176.silk"

gradlePlugin {
    plugins {
        create("silkPlugin") {
            id = "de.rhm176.silk.silk-plugin"
            implementationClass = "de.rhm176.silk.SilkPlugin"
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:${project.property("jacksonVersion")}")
    implementation("org.ow2.asm:asm:${project.property("asmVersion")}")

    compileOnly("org.jetbrains:annotations:${project.property("annotationsVersion")}")
}

java {
    val javaLanguageVersion = JavaLanguageVersion.of(project.findProperty("javaVersion").toString())
    val javaVersion = JavaVersion.toVersion(javaLanguageVersion.asInt())

    toolchain {
        languageVersion = javaLanguageVersion
    }

    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion

    withSourcesJar()
}

publishing {
    repositories {
        val reposiliteBaseUrl = System.getenv("REPOSILITE_URL")
            ?: project.findProperty("reposiliteUrl") as String?

        if (!reposiliteBaseUrl.isNullOrBlank()) {
            maven {
                name = "Reposilite"

                val repoPath = if (project.version.toString().endsWith("-SNAPSHOT")) {
                    "/snapshots"
                } else {
                    "/releases"
                }
                url = uri("$reposiliteBaseUrl$repoPath")

                credentials(PasswordCredentials::class.java) {
                    username = System.getenv("REPOSILITE_USERNAME") ?: project.findProperty("reposiliteUsername") as String?
                    password = System.getenv("REPOSILITE_PASSWORD") ?: project.findProperty("reposilitePassword") as String?
                }

                authentication {
                    create<BasicAuthentication>("basic")
                }
            }
        }
    }
}

spotless {
    java {
        licenseHeaderFile(rootProject.file("HEADER"))

        importOrder()
        removeUnusedImports()

        palantirJavaFormat("2.66.0")
    }
}