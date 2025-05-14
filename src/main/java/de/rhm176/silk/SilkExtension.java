package de.rhm176.silk;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.ApiStatus;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private Provider<RegularFile> internalGameJarProvider;
    private final DirectoryProperty runDir;
    private final Project project;

    /**
     * Constructor for dependency injection by Gradle.
     * @param objectFactory Gradle's {@link ObjectFactory} for creating domain objects.
     */
    @Inject
    public SilkExtension(ObjectFactory objectFactory, Project project) {
        // default set in SilkPlugin
        this.runDir = objectFactory.directoryProperty();
        this.project = project;
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
                project.getLogger().debug("No files found in '{}' configuration for game JAR.", equilinoxConfig.getName());
                return null;
            }
            if (files.size() > 1) {
                project.getLogger().warn(
                        "Multiple files found in the '{}' configuration. Using the first one: {}. " +
                                "Please ensure only one game JAR is specified.",
                        equilinoxConfig.getName(),
                        files.get(0).getAbsolutePath()
                );
            }
            return files.get(0);
        });

        this.internalGameJarProvider = project.getLayout().file(singleFileProvider);
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
            throw new IllegalStateException("SilkExtension.internalGameJarProvider has not been initialized. " +
                    "This is likely an internal plugin error.");
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
     * @return A {@link File} object pointing to the Equilinox game JAR.
     * @throws GradleException if the game JAR cannot be found after searching known locations.
     */
    public File findEquilinoxGameJar() {
        String os = System.getProperty("os.name").toLowerCase();
        List<String> potentialJarNames = new ArrayList<>();

        if (os.contains("win")) {
            potentialJarNames.add("EquilinoxWindows.jar");
        } else if (os.contains("mac")) {
            potentialJarNames.add("Equilinox.jar");
            potentialJarNames.add("EquilinoxMac.jar");
        } else {
            potentialJarNames.add("Equilinox.jar");
            potentialJarNames.add("EquilinoxLinux.jar");
        }

        List<Path> steamLibraryRoots = findSteamLibraryRoots(os);

        project.getLogger().info("Silk: Searching for Equilinox JAR in {} Steam library roots...", steamLibraryRoots.size());

        for (Path libraryRoot : steamLibraryRoots) {
            Path commonDir = libraryRoot.resolve("steamapps").resolve("common");
            Path equilinoxGameDir = commonDir.resolve("Equilinox");

            if (Files.isDirectory(equilinoxGameDir)) {
                for (String jarName : potentialJarNames) {
                    Path gameJarPath = equilinoxGameDir.resolve(jarName);
                    if (Files.isRegularFile(gameJarPath)) {
                        return gameJarPath.toFile();
                    }

                    if (os.contains("mac")) {
                        Path appBundlePath = equilinoxGameDir.resolve("Equilinox.app");
                        if(Files.isDirectory(appBundlePath)) {
                            List<Path> macJarPaths = Arrays.asList(
                                    appBundlePath.resolve("Contents/Java/Equilinox.jar"),
                                    appBundlePath.resolve("Contents/Resources/Java/Equilinox.jar"),
                                    appBundlePath.resolve("Contents/MacOS/Equilinox.jar")
                            );
                            for (Path macJarPath : macJarPaths) {
                                if (Files.isRegularFile(macJarPath)) {
                                    return macJarPath.toFile();
                                }
                            }
                        }
                    }
                }
            }
        }

        project.getLogger().error("Silk: Equilinox game JAR could not be found automatically.");
        throw new GradleException("Silk plugin: Could not automatically find Equilinox game JAR. " +
                "Searched common Steam locations.");
    }

    private List<Path> findSteamLibraryRoots(String os) {
        List<Path> roots = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        if (os.contains("win")) {
            addPotentialRoot(roots, Paths.get("C:\\Program Files (x86)\\Steam"));
            addPotentialRoot(roots, Paths.get("C:\\Program Files\\Steam"));
            addPotentialRoot(roots, Paths.get("C:\\Users\\RHM\\scoop\\apps\\steam\\current"));
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
            vdfPossibleLocations.add(Paths.get(userHome, "Library", "Application Support", "Steam", "steamapps", "libraryfolders.vdf"));
        } else {
            vdfPossibleLocations.add(Paths.get(userHome, ".steam", "steam", "steamapps", "libraryfolders.vdf"));
            vdfPossibleLocations.add(Paths.get(userHome, ".local", "share", "Steam", "steamapps", "libraryfolders.vdf"));
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
                    project.getLogger().warn("Silk: Could not read Steam libraryfolders.vdf at {}: {}", vdfPath, e.getMessage());
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