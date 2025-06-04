/*
 * Copyright (c) 2025 Silk Loader
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package de.rhm176.silk;

import de.rhm176.silk.extension.FabricExtension;
import de.rhm176.silk.extension.VineflowerExtension;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.VisibleForTesting;

/**
 * Configuration extension for the Silk Gradle plugin.
 * <p>
 * This extension is used to configure settings related to the Equilinox modding
 * environment, such as the game JAR, run directory, and mod loader main class.
 * It is accessed in a Gradle build script via the {@code silk} block:
 * <pre>
 * silk {
 *     runDir.set(layout.projectDirectory.dir("run"))
 * }
 * </pre>
 */
public abstract class SilkExtension {
    /**
     * Container for registering subprojects to be bundled with the main mod.
     * Accessed via {@code silk.mods { ... }} in the build script.
     */
    public static class ModRegistration {
        private final Project rootProject;
        private final List<Project> targetList;

        /**
         * Constructs a ModRegistration container.
         * This constructor is typically called by Gradle's {@link ObjectFactory}.
         *
         * @param rootProject The root project to which the Silk plugin is applied.
         * @param targetList  The list in {@link SilkExtension} where registered subprojects are stored.
         */
        @Inject
        public ModRegistration(Project rootProject, List<Project> targetList) {
            this.rootProject = rootProject;
            this.targetList = targetList;
        }

        /**
         * Registers a subproject to be bundled.
         * <p>
         * The specified subproject's JAR output will be included in the
         * {@code META-INF/jars/} directory of the main mod's JAR, and an
         * entry will be added to the main mod's {@code fabric.mod.json}.
         *
         * @param subProject The Gradle {@link Project} instance of the subproject to bundle (e.g., {@code project(":submoduleA")}).
         */
        public void register(Project subProject) {
            if (subProject == null) {
                rootProject
                        .getLogger()
                        .warn("Silk: Attempted to register a null subproject in 'silk.mods.register()'.");
                return;
            }
            if (!targetList.contains(subProject)) {
                targetList.add(subProject);
            } else {
                rootProject
                        .getLogger()
                        .warn(
                                "Silk: Tried to register subproject '{}' for bundling twice. Ignoring.",
                                subProject.getPath());
            }
        }
    }

    // If the game can't be found by name, it will search all jars in the
    // Equilinox install dir and if all of the listed classes are found,
    // the jar is determined to be the game.
    @VisibleForTesting
    static final List<String> EQUILINOX_CLASS_FILES = List.of("main/MainApp.class", "main/FirstScreenUi.class");

    private Provider<RegularFile> internalGameJarProvider;
    private final DirectoryProperty runDir;
    private final Project project;
    private final VineflowerExtension vineflower;
    private final FabricExtension fabric;
    private final ModRegistration modsContainer;
    private final List<Project> registeredSubprojects = new ArrayList<>();
    private final Property<Boolean> generateFabricModJson;
    private final Property<Boolean> verifyFabricModJson;
    private final Property<Object> silkLoaderCoordinates;
    private final Property<String> silkLoaderMainClassOverride;

    @VisibleForTesting
    FileCollection cachedEquilinoxGameJarFc = null;

    /**
     * @param objectFactory The Gradle {@link ObjectFactory} service, used for creating domain objects.
     * @param project The Gradle {@link Project} this extension is associated with.
     */
    @Inject
    public SilkExtension(ObjectFactory objectFactory, Project project) {
        // default set in SilkPlugin
        this.runDir = objectFactory.directoryProperty();

        this.project = project;

        this.vineflower = objectFactory.newInstance(VineflowerExtension.class);

        this.fabric = objectFactory.newInstance(FabricExtension.class, objectFactory, project);
        this.generateFabricModJson = objectFactory.property(Boolean.class).convention(false);
        this.verifyFabricModJson = objectFactory.property(Boolean.class).convention(true);

        this.silkLoaderCoordinates = objectFactory.property(Object.class);
        this.silkLoaderMainClassOverride = objectFactory.property(String.class);

        this.modsContainer = objectFactory.newInstance(ModRegistration.class, project, registeredSubprojects);
    }

    /**
     * Specifies the main class to use when launching the game.
     * If not set, will instead try to determine the main class
     * from the Silk Loader, which does not work on older versions.
     * @return The property containing the main class
     */
    public Property<String> getSilkLoaderMainClassOverride() {
        return silkLoaderMainClassOverride;
    }

    /**
     * Specifies the loader dependency coordinates in "group:artifact:version" format.
     * The Silk plugin will add this as a dependency to the project.
     * @return The property containing the silk loader coordinates
     */
    public Property<Object> getSilkLoaderCoordinates() {
        return silkLoaderCoordinates;
    }

    /**
     * Controls whether the parameters configured for {@code fabric.mod.json} generation
     * via the {@link #fabric(Action)} block (which configures {@link FabricExtension})
     * should be verified for correctness by the {@link de.rhm176.silk.task.GenerateFabricJsonTask}.
     * <p>
     * When set to {@code true} (the default), the generation task will perform a series of checks
     * on the configured values (e.g., for mod ID format, presence of mandatory fields,
     * validity of certain paths or URLs, etc.) and fail the build if issues are found.
     * This helps catch common errors early.
     * <p>
     * When set to {@code false}, these built-in validations within the
     * {@code GenerateFabricJsonTask} are skipped. This might be useful in advanced scenarios
     * or if the user wishes to bypass specific checks, but it increases the risk of generating an
     * invalid {@code fabric.mod.json} file.
     * <p>
     * Note that the verification might not be exhaustive for all possible {@code fabric.mod.json}
     * specification details (only some things are checked, and only to a specific extent), focusing on common requirements and formats.
     * The ultimate validation is performed by Fabric Loader at runtime.
     *
     * @return A {@link Property} of {@link Boolean} to enable or disable {@code fabric.mod.json} validation.
     */
    public Property<Boolean> getVerifyFabricModJson() {
        return verifyFabricModJson;
    }

    /**
     * Controls whether the {@code fabric.mod.json} file should be generated by this plugin
     * based on the configuration in {@link #fabric(Action)}.
     * <p>
     * Defaults to {@code false}. If {@code false}, the loader and plugin expect a manually created
     * {@code src/main/resources/fabric.mod.json} file.
     * <p>
     * If set to {@code true}, the plugin will generate the file using {@link de.rhm176.silk.task.GenerateFabricJsonTask}.
     * An error will be thrown if a manual {@code src/main/resources/fabric.mod.json}
     * also exists when generation is enabled.
     *
     * @return Property to enable/disable {@code fabric.mod.json} generation.
     */
    public Property<Boolean> getGenerateFabricModJson() {
        return generateFabricModJson;
    }

    /**
     * Accesses the configuration for the {@code fabric.mod.json} file's contents.
     *
     * @return The configuration object for {@code fabric.mod.json} fields.
     */
    public FabricExtension getFabric() {
        return fabric;
    }

    /**
     * Configures the properties for the generated {@code fabric.mod.json} file.
     *
     * @param action The configuration action for {@link FabricExtension}.
     */
    public void fabric(Action<? super FabricExtension> action) {
        action.execute(fabric);
    }

    /**
     * Provides access to the {@link ModRegistration} container for configuring bundled submods.
     * <p>
     * Example in {@code build.gradle.kts}:
     * <pre>
     * silk {
     *     mods.register(project(":mySubmod"))
     * }
     * </pre>
     * Or in {@code build.gradle}:
     * <pre>
     * silk {
     *     mods {
     *         register project(':mySubmod')
     *     }
     * }
     * </pre>
     *
     * @return The {@link ModRegistration} instance.
     */
    public ModRegistration getMods() {
        return modsContainer;
    }

    /**
     * Configures the bundled submods using an {@link Action}.
     * <p>
     * This method allows for a more idiomatic configuration block in Groovy-based Gradle scripts:
     * <pre>
     * silk {
     *     mods {
     *         register project(':mySubmod')
     *
     *     }
     * }
     * </pre>
     *
     * @param action The configuration action for {@link ModRegistration}.
     */
    public void mods(Action<? super ModRegistration> action) {
        action.execute(modsContainer);
    }

    /**
     * Retrieves an unmodifiable list of subprojects registered for bundling.
     * This method is intended for internal use by the plugin.
     *
     * @return An unmodifiable list of {@link Project} instances.
     */
    @ApiStatus.Internal
    List<Project> getRegisteredSubprojectsInternal() {
        return Collections.unmodifiableList(registeredSubprojects);
    }

    /**
     * Initializes the provider for the game JAR based on the specified Gradle {@link Configuration}.
     * <p>
     * This method is intended for internal use by the plugin to set up the game JAR.
     * It expects the configuration to resolve to a single JAR file.
     *
     * @param equilinoxConfig The Gradle {@link Configuration} that should contain the game JAR.
     * @param project The current {@link Project}.
     */
    @ApiStatus.Internal
    void initializeGameJarProvider(Configuration equilinoxConfig, Project project) {
        Provider<File> singleFileProvider = project.provider(() -> {
            List<File> files = equilinoxConfig.getFiles().stream().toList();
            if (files.isEmpty()) {
                project.getLogger()
                        .debug("Silk: No files found in '{}' configuration for game JAR.", equilinoxConfig.getName());
                return null;
            }
            if (files.size() > 1) {
                project.getLogger()
                        .warn(
                                "Silk: Multiple files found in the '{}' configuration. Using the first one: {}. "
                                        + "Please ensure only one game JAR is specified.",
                                equilinoxConfig.getName(),
                                files.get(0).getAbsolutePath());
            }
            project.getLogger().info("TEST: {}", files.toString());
            return files.get(0);
        });

        this.internalGameJarProvider = project.getLayout().file(singleFileProvider);
    }

    /**
     * Provides access to the Vineflower decompiler specific configurations.
     * <p>
     * Use this to set the Vineflower version or add custom decompiler arguments.
     * Example in {@code build.gradle.kts}:
     * <pre>
     * silk {
     *     vineflower.version.set("1.9.3")
     *     vineflower.args.add("-myarg=value")
     * }
     * </pre>
     *
     * @return The {@link VineflowerExtension} instance.
     */
    public VineflowerExtension getVineflower() {
        return vineflower;
    }

    /**
     * Configures the Vineflower decompiler settings using an {@link Action}.
     * <p>
     * This method allows for a more idiomatic configuration block in Groovy-based Gradle scripts:
     * <pre>
     * silk {
     *     vineflower {
     *         version = "1.9.3"
     *         args &lt;&lt; "-myarg=value"
     *     }
     * }
     * </pre>
     *
     * @param action The configuration action for Vineflower settings.
     */
    public void vineflower(Action<? super VineflowerExtension> action) {
        action.execute(vineflower);
    }

    /**
     * Provides the main game JAR file (e.g., EquilinoxWindows.jar).
     * <p>
     * This property is read-only from the perspective of the build script user configuring the extension.
     *
     * @return A {@link Provider} of the {@link RegularFile} representing the game JAR.
     * @throws IllegalStateException if the game JAR provider has not been initialized by the plugin.
     */
    public Provider<RegularFile> getGameJar() {
        if (this.internalGameJarProvider == null) {
            throw new IllegalStateException("SilkExtension.internalGameJarProvider has not been initialized. "
                    + "This is likely an internal plugin error.");
        }
        return internalGameJarProvider;
    }

    /**
     * The directory where the game will be launched.
     * <p>
     * Users can configure this to specify a custom run directory for development or testing.
     * Example in {@code build.gradle.kts}:
     * <pre>
     * silk {
     *     runDir.set(layout.projectDirectory.dir("my_custom_run_dir"))
     * }
     * </pre>
     * A default value is typically set by the {@link SilkPlugin}.
     *
     * @return A {@link DirectoryProperty} representing the run directory.
     */
    public DirectoryProperty getRunDir() {
        return runDir;
    }

    /**
     * Attempts to automatically find the Equilinox game JAR based on common Steam installation locations.
     * <p>
     * Can and should be used in conjunction with the equilinox configuration:
     * <pre>
     * dependencies {
     *     equilinox(silk.findEquilinoxGameJar())
     * }
     * </pre>
     * Note: This may not always work. If the game can't be found automatically, specify it using:
     * <pre>
     * dependencies {
     *     equilinox(files("path/to/Equilinox.jar"))
     * }
     * </pre>
     *
     * @return A {@link FileCollection} object pointing to the Equilinox game JAR.
     * @throws GradleException if the game JAR cannot be found after searching known locations.
     */
    public FileCollection findEquilinoxGameJar() {
        if (this.cachedEquilinoxGameJarFc != null) {
            if (!this.cachedEquilinoxGameJarFc.isEmpty()
                    && this.cachedEquilinoxGameJarFc.getSingleFile().exists()) {
                return this.cachedEquilinoxGameJarFc;
            } else {
                this.cachedEquilinoxGameJarFc = null;
            }
        }

        String os = System.getProperty("os.name").toLowerCase();
        List<String> potentialJarNames = new ArrayList<>();

        if (os.contains("win")) {
            potentialJarNames.add("Equilinox.jar");
            potentialJarNames.add("EquilinoxWindows.jar");
            potentialJarNames.add("input.jar"); // can never hurt
        } else if (os.contains("mac")) {
            potentialJarNames.add("Equilinox.jar");
            potentialJarNames.add("EquilinoxMac.jar");
            potentialJarNames.add("input.jar"); // maybe on mac also?
        } else {
            potentialJarNames.add("Equilinox.jar");
            potentialJarNames.add("EquilinoxLinux.jar");
            potentialJarNames.add("input.jar"); // ??? wtf
        }

        List<Path> steamLibraryRoots = findSteamLibraryRoots(os);

        for (Path libraryRoot : steamLibraryRoots) {
            Path commonDir = libraryRoot.resolve("steamapps").resolve("common");
            Path equilinoxGameDir = commonDir.resolve("Equilinox");

            if (Files.isDirectory(equilinoxGameDir)) {
                for (String jarName : potentialJarNames) {
                    Path gameJarPath = equilinoxGameDir.resolve(jarName);
                    if (Files.isRegularFile(gameJarPath)) {
                        this.cachedEquilinoxGameJarFc = project.files(gameJarPath.toFile());
                        return this.cachedEquilinoxGameJarFc;
                    }
                }

                try (Stream<Path> stream = Files.list(equilinoxGameDir)) {
                    List<Path> jarFilesInDir = stream.filter(p -> Files.isRegularFile(p)
                                    && p.getFileName().toString().toLowerCase().endsWith(".jar"))
                            .collect(Collectors.toList());

                    for (Path potentialJar : jarFilesInDir) {
                        if (isCorrectGameJarByContent(potentialJar)) {
                            this.cachedEquilinoxGameJarFc = project.files(potentialJar.toFile());
                            return this.cachedEquilinoxGameJarFc;
                        }
                    }
                } catch (IOException e) {
                    project.getLogger()
                            .warn(
                                    "Silk: Error listing/processing JARs for content check in {}: {}",
                                    equilinoxGameDir,
                                    e.getMessage());
                }
            }
        }

        throw new GradleException(
                "Silk plugin: Could not automatically find Equilinox game JAR. " + "Searched common Steam locations.");
    }

    @VisibleForTesting
    boolean isCorrectGameJarByContent(Path jarPath) {
        if (!Files.isRegularFile(jarPath) || !jarPath.toString().toLowerCase().endsWith(".jar")) {
            return false;
        }

        try (FileSystem jarFs = FileSystems.newFileSystem(jarPath, Map.of())) {
            for (String classEntry : EQUILINOX_CLASS_FILES) {
                Path entryPathInJar = jarFs.getPath(classEntry);
                if (Files.notExists(entryPathInJar)) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            project.getLogger()
                    .debug("Silk: Could not inspect JAR file {} for content matching: {}", jarPath, e.getMessage());
            return false;
        }
    }

    @VisibleForTesting
    protected List<Path> findSteamLibraryRoots(String os) {
        List<Path> roots = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            addPotentialRoot(roots, Paths.get("C:\\Program Files (x86)\\Steam"));
            addPotentialRoot(roots, Paths.get("C:\\Program Files\\Steam"));
            addPotentialRoot(roots, Paths.get(userHome, "scoop", "apps", "steam", "current"));
        } else if (os.contains("mac")) {
            addPotentialRoot(roots, Paths.get(userHome, "Library", "Application Support", "Steam"));
        } else {
            addPotentialRoot(roots, Paths.get(userHome, ".steam", "steam"));
            addPotentialRoot(roots, Paths.get(userHome, ".local", "share", "Steam"));
            addPotentialRoot(roots, Paths.get(userHome, ".var", "app", "com.valvesoftware.Steam", ".steam", "steam"));
        }

        List<Path> vdfPossibleLocations = new ArrayList<>();
        for (Path rootCandidate : new ArrayList<>(roots)) {
            vdfPossibleLocations.add(rootCandidate.resolve("steamapps").resolve("libraryfolders.vdf"));
        }
        if (os.contains("win")) {
            vdfPossibleLocations.add(Paths.get("C:\\Program Files (x86)\\Steam\\steamapps\\libraryfolders.vdf"));
        } else if (os.contains("mac")) {
            vdfPossibleLocations.add(
                    Paths.get(userHome, "Library", "Application Support", "Steam", "steamapps", "libraryfolders.vdf"));
        } else {
            vdfPossibleLocations.add(Paths.get(userHome, ".steam", "steam", "steamapps", "libraryfolders.vdf"));
            vdfPossibleLocations.add(
                    Paths.get(userHome, ".local", "share", "Steam", "steamapps", "libraryfolders.vdf"));
        }

        Pattern pathPattern = Pattern.compile("^\\s*\"path\"\\s+\"([^\"]+)\"");
        for (Path vdfPath : vdfPossibleLocations.stream().distinct().toList()) {
            if (Files.isRegularFile(vdfPath)) {
                try (Stream<String> lines = Files.lines(vdfPath)) {
                    lines.forEach(line -> {
                        Matcher matcher = pathPattern.matcher(line);
                        if (matcher.find()) {
                            String steamLibPath = matcher.group(1).replace("\\\\", "\\");
                            addPotentialRoot(roots, Paths.get(steamLibPath));
                        }
                    });
                } catch (IOException e) {
                    project.getLogger()
                            .warn("Silk: Could not read Steam libraryfolders.vdf at {}: {}", vdfPath, e.getMessage());
                }
            }
        }
        return roots.stream().distinct().filter(Files::isDirectory).collect(Collectors.toList());
    }

    private void addPotentialRoot(List<Path> roots, Path path) {
        if (Files.isDirectory(path) && !roots.contains(path)) {
            roots.add(path);
        }
    }
}
