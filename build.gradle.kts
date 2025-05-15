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
            id = "de.rhm176.silk"
            implementationClass = "de.rhm176.silk.SilkPlugin"
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.jetbrains:annotations:${rootProject.findProperty("annotationsVersion")}")
}

java {
    withSourcesJar()
    withJavadocJar()

    val javaLanguageVersion = JavaLanguageVersion.of(rootProject.findProperty("javaVersion").toString())
    val javaVersion = JavaVersion.toVersion(javaLanguageVersion.asInt())

    toolchain {
        languageVersion = javaLanguageVersion
    }

    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            artifactId = "silk-plugin"
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