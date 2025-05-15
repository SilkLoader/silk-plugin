plugins {
    id("de.rhm176.silk")
}

dependencies {
    equilinox(silk.findEquilinoxGameJar())

    implementation("com.github.SilkLoader:silk-loader:v1.0.1")
}