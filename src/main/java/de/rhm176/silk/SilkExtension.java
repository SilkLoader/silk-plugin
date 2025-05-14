package de.rhm176.silk;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFile;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.jetbrains.annotations.ApiStatus;

import javax.inject.Inject;
import java.io.File;
import java.util.List;

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

    /**
     * Constructor for dependency injection by Gradle.
     * @param objectFactory Gradle's {@link ObjectFactory} for creating domain objects.
     */
    @Inject
    public SilkExtension(ObjectFactory objectFactory) {
        // default set in SilkPlugin
        this.runDir = objectFactory.directoryProperty();
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
}