rootProject.name = "silk-plugin"

val isJitpackBuild = System.getenv("JITPACK") == "true"
val isCIBuild = System.getenv("CI") == "true"

pluginManagement {
    includeBuild("plugin")
}

if (!isJitpackBuild && !isCIBuild) {
    include(":testmod")
}