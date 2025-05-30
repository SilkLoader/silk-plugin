# Silk Gradle Plugin

**Silk Gradle Plugin is a tool designed to streamline the development of [Fabric](https://fabricmc.net/) mods for the game [Equilinox](https://www.equilinox.com/)**

## Features
 * **Automatic Repository Setup:** Adds FabricMC, Maven Central, and JitPack repositories to your project if they are not already present.
 * **(Optional) `fabric.mod.json` Generation:**
    * Generates the `fabric.mod.json` file based on a dedicated configuration block in your `build.gradle(.kts)`.
    * Optionally verifies the generated `fabric.mod.json` against the schema.
 * **Subproject/Bundled Mod Support:**
    * Automatically includes JARs from specified subprojects (project dependencies) into your main mod's `META-INF/jars/` directory.
    * Updates `fabric.mod.json` to reference these bundled JARs, making multi-module mod development seamless.
 * **Development Workflow:**
    * **`runGame`**: Configures and launches Equilinox with your mod, its dependencies, and necessary JVM arguments for easy testing and debugging directly from Gradle.

## Prerequisites

* **Java Development Kit (JDK):** Version 17 or newer is required.
* **Gradle**
* **Equilinox:** You need a valid copy of the Equilinox game JAR.

## Setup

1.  **Apply the Plugin:**
    Add the Silk Gradle Plugin to your `build.gradle.kts` (Kotlin DSL) or `build.gradle` (Groovy DSL).

    **Kotlin DSL (`build.gradle.kts`/`settings.gradle.kts`):**
    ```kotlin
    plugins {
        id("de.rhm176.silk") version "<version>"
    }
    ```
    ```kotlin
    pluginManagement {
        resolutionStrategy {
            eachPlugin {
                requested.apply {
                    if ("$id" == "de.rhm176.silk") {
                        useModule("com.github.SilkLoader:silk-plugin:v$version")
                    }
                }
            }
        }
    
        repositories {
            maven("https://jitpack.io")
            gradlePluginPortal()
        }
    }
    ```

    **Groovy DSL (`build.gradle`/`settings.gradle`):**
    ```groovy
    plugins {
        id 'de.rhm176.silk' version '<version>'
    }
    ```
    ```groovy
    pluginManagement {
        resolutionStrategy {
            eachPlugin {
                if (requested.id.name == 'de.rhm176.silk') { // or requested.id.toString() == 'de.rhm176.silk'
                    useModule("com.github.SilkLoader:silk-plugin:v${requested.version}")
                }
            }
        }
    
        repositories {
            maven {
                url 'https://jitpack.io'
            }
            gradlePluginPortal()
        }
    }
    ```
    *(Note: Replace `<version>` with the version of the Silk Gradle Plugin you want to use. You can see all available versions on JitPack.)*

## Configuration

Configure the plugin using the `silk { ... }` extension block in your Gradle build script.

1.  **Specifying the Equilinox Game JAR:**
    The plugin requires the Equilinox game JAR. You provide this by adding it as a dependency to the `equilinox` configuration.

    **Kotlin DSL (`build.gradle.kts`) & Groovy DSL (`build.gradle`):**
    ```kotlin
    dependencies {
        equilinox(files("path/to/your/EquilinoxGame.jar"))
        //or
        equilinox(silk.findEquilinoxGameJar())
    }
    ```
2.  **The `silk` Extension Block:**

    **Kotlin DSL (`build.gradle.kts`):**
    ```kotlin
    silk {
        // Directory for the `runGame` task. Default: "project_dir/run"
        runDir.set(layout.projectDirectory.dir("run_equilinox"))

        // --- Fabric Mod Manifest (fabric.mod.json) ---
        // Set to true to generate fabric.mod.json from the `fabric` block below.
        // Default: false (expects a manual src/main/resources/fabric.mod.json).
        generateFabricModJson.set(true)

        // If true (default), validates the generated/manual fabric.mod.json against the schema.
        // verifyFabricModJson.set(true)

        // Configuration for the `fabric` manifest (used if generateFabricModJson is true)
        fabric {
            id.set("your_mod_id")
            version.set(project.version.toString()) // Or a fixed version string
            name.set("Your Awesome Mod")
            description.set("This mod makes Equilinox even more awesome.")
            authors.set(listOf("Your Name", "Another Author"))
            // contributors.set(listOf("Helpful Contributor"))
            contact.set(mapOf(
                "homepage" to "[https://your.mod.homepage.com](https://your.mod.homepage.com)",
                "sources" to "[https://github.com/yourusername/your-mod-repo](https://github.com/yourusername/your-mod-repo)",
                "issues" to "[https://github.com/yourusername/your-mod-repo/issues](https://github.com/yourusername/your-mod-repo/issues)"
            ))
            licenses.set(listOf("MIT")) // Or your preferred license ID (e.g., "ARR", "Apache-2.0")
            // iconFile.set("assets/your_mod_id/icon.png") // Path to your mod icon

            entrypoints {
                main.add("com.example.yourmod.YourModMainClass")
                // client.add("com.example.yourmod.YourModClientClass")
                // server.add("com.example.yourmod.YourModServerClass") // If applicable
            }

            // List of your mixin configuration JSON files
            mixins.add("your_mod_id.mixins.json")
            // mixins.add("your_mod_id.client.mixins.json")

            // Dependencies on other mods or Fabric API
            depends.put("fabricloader", ">=0.15.0") // Recommended Fabric Loader version
            depends.put("fabric-api", "*")          // Example: Depend on any version of Fabric API
            // depends.put("equilinox", ">=1.X.Y")  // If you target a specific game version range recognized by Fabric
            // recommends.put("another_cool_mod", ">=1.2.0")

            // Path to your access widener file (e.g., "your_mod_id.accesswidener")
            // accessWidener.set("your_mod_id.accesswidener")

            // Custom values for fabric.mod.json
            // customData.put("myCustomKey", mapOf("some_data" to true, "value" to 42))
        }

        // --- Vineflower Decompiler Settings (for genSources task) ---
        vineflower {
            version.set("1.9.3") // Specify the Vineflower version to use
            // args.set(listOf("--threads=4", "-Xmx1G")) // Optional: custom arguments for Vineflower
        }
    }
    ```

    **Groovy DSL (`build.gradle`):**
    ```groovy
    silk {
        runDir = layout.projectDirectory.dir("run_equilinox")

        generateFabricModJson = true
        // verifyFabricModJson = true

        fabric {
            id = "your_mod_id"
            version = project.version.toString()
            name = "Your Awesome Mod"
            description = "This mod makes Equilinox even more awesome."
            authors = ["Your Name", "Another Author"]
            // contributors = ["Helpful Contributor"]
            contact = [
                homepage: "[https://your.mod.homepage.com](https://your.mod.homepage.com)",
                sources: "[https://github.com/yourusername/your-mod-repo](https://github.com/yourusername/your-mod-repo)",
                issues: "[https://github.com/yourusername/your-mod-repo/issues](https://github.com/yourusername/your-mod-repo/issues)"
            ]
            licenses = ["MIT"]
            // iconFile = "assets/your_mod_id/icon.png"

            entrypoints {
                main.add "com.example.yourmod.YourModMainClass"
                // client.add "com.example.yourmod.YourModClientClass"
            }

            mixins.add "your_mod_id.mixins.json"

            depends.put "fabricloader", ">=0.15.0"
            depends.put "fabric-api", "*"
            // depends.put "equilinox", ">=1.X.Y"
            // recommends.put "another_cool_mod", ">=1.2.0"

            // accessWidener = "your_mod_id.accesswidener"

            // customData.put "myCustomKey", [some_data: true, value: 42]
        }

        vineflower {
            version = "1.9.3"
            // args = ["--threads=4", "-Xmx1G"]
        }
    }
    ```
    **Important Note on `generateFabricModJson`:**
    * If `generateFabricModJson` is set to `true`, the plugin generates `fabric.mod.json` into `build/generated/silk/resources/main/fabric.mod.json`. In this mode, you **must not** have a manual `fabric.mod.json` at `src/main/resources/fabric.mod.json`, as this will cause a build error.
    * If `generateFabricModJson` is `false` (the default), you are responsible for providing and maintaining `src/main/resources/fabric.mod.json` yourself. The `modifyFabricModJson` task will still attempt to update this manual file if you bundle subprojects.

3.  **Bundling Subprojects (Multi-project Builds):**
    If your mod consists of multiple subprojects (modules) where some produce JARs that need to be included within your main mod JAR:
    1.  Ensure the subprojects are standard Java projects that produce a JAR (e.g., apply the `java` or `java-library` plugin to them).
    2. Register these subprojects in your main mod project's `build.gradle(.kts)` file using the `register()` method within the `silk.mods` block.

    The Silk plugin will then automatically include them in your main mod jar and add them to the `fabric.mod.json`.
    **Kotlin DSL (`build.gradle.kts`):**
    ```kotlin
    silk {
        // ... other silk configurations ...

        mods {
            register(project(":submoduleA"))
            register(project(":submoduleB"))
            // Add more subprojects as needed
        }
    }
    ```

    **Groovy DSL (`build.gradle`):**
    ```groovy
    silk {
        // ... other silk configurations ...
    
        mods {
            register project(':submoduleA')
            register project(':submoduleB')
            // Add more subprojects as needed
        }
    }
    ```
## Provided Tasks

The Silk Gradle Plugin configures and adds several useful tasks:

* **`genSources` (Group: `Silk`)**: Decompiles the transformed game JAR using Vineflower. Output is a sources JAR in `build/silk/sources/`.
    * Execute: `./gradlew genSources`
* **`extractNatives` (Group: `Silk`)**: Extracts native libraries from the game JAR into `build/silk/natives/`. Used by `runGame`.
    * Execute: `./gradlew extractNatives` (usually run as a dependency of `runGame`)
* **`runGame` (Group: `Silk`)**: Launches Equilinox with your mod, its dependencies, and required natives and JVM arguments.
    * Working directory is configured by `silk.runDir`.
    * Execute: `./gradlew runGame`
