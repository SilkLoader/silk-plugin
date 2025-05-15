plugins {
    id("java-gradle-plugin")
    id("maven-publish")
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

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            artifactId = "silk-plugin"
        }
    }
}