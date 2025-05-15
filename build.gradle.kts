plugins {
    id("java")
    id("com.diffplug.spotless") version "7.0.3"
}

allprojects {
    apply(plugin = "java")
    apply(plugin = "com.diffplug.spotless")

    repositories {
        mavenCentral()
    }

    dependencies {
        compileOnly("org.jetbrains:annotations:${rootProject.findProperty("annotationsVersion")}")
    }

    java {
        val javaLanguageVersion = JavaLanguageVersion.of(rootProject.findProperty("javaVersion").toString())
        val javaVersion = JavaVersion.toVersion(javaLanguageVersion.asInt())

        toolchain {
            languageVersion = javaLanguageVersion
        }

        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    spotless {
        java {
            licenseHeaderFile(rootProject.file("HEADER"))

            importOrder()
            removeUnusedImports()

            palantirJavaFormat("2.66.0")
        }
    }
}