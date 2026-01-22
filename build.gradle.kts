import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("java-gradle-plugin")
    id("maven-publish")
    id("com.diffplug.spotless") version "7.0.3"
    id("com.gradleup.shadow") version "9.3.1"
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
    maven {
        url = uri("https://maven.fabricmc.net")
        name = "FabricMC"
    }
}

val shadowBundle: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:${project.property("jacksonVersion")}")
    implementation("org.ow2.asm:asm:${project.property("asmVersion")}")
    implementation("org.ow2.asm:asm-tree:${project.property("asmVersion")}")
    "net.fabricmc:class-tweaker:${property("classTweakerVersion")}".let {
        implementation(it)
        shadowBundle(it)
    }

    compileOnly("org.jetbrains:annotations:${project.property("annotationsVersion")}")

    testImplementation(gradleTestKit())

    testImplementation("com.github.stefanbirkner:system-lambda:${project.property("systemLambdaVersion")}")
    testImplementation("com.google.jimfs:jimfs:${project.property("jimfsVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testImplementation("org.mockito:mockito-core:${project.property("mockitoVersion")}")
    testImplementation("org.mockito:mockito-junit-jupiter:${project.property("mockitoVersion")}")
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    configurations = listOf(shadowBundle)
    mergeServiceFiles()

    relocate("net.fabricmc.classtweaker", "de.rhm176.silk.shadow.classtweaker")
}


tasks.test {
    useJUnitPlatform()
}

java {
    val javaVersion = JavaVersion.toVersion(project.findProperty("javaVersion").toString())

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

    publications {
        withType<MavenPublication> {
            artifact(tasks.named("shadowJar"))

            pom.withXml {
                val dependenciesNode = asNode().get("dependencies") as? groovy.util.Node
                dependenciesNode?.children()?.removeIf {
                    val dependency = it as groovy.util.Node
                    val artifactId = dependency.get("artifactId") as String
                    artifactId == "class-tweaker"
                }
            }
        }
    }
}

tasks.jar {
    enabled = false
}

spotless {
    java {
        licenseHeaderFile(rootProject.file("HEADER"))

        importOrder()
        removeUnusedImports()

        palantirJavaFormat("2.66.0")
    }
}