package de.rhm176.silk;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;

import java.io.File;

/**
 * Main plugin class for Silk, a Gradle plugin to facilitate mod development for Equilinox.
 * <p>
 * This plugin performs several actions:
 * <ul>
 * <li>Creates an {@code equilinox} configuration for specifying the game JAR.</li>
 * <li>Registers the {@link SilkExtension} under the name {@code silk} for user configuration.</li>
 * <li>Registers a {@code runGame} task of type {@link JavaExec} to launch the game with the mod.</li>
 * <li>Adds the configured game JAR as a {@code compileOnly} dependency to the project.</li>
 * </ul>
 */
public class SilkPlugin implements Plugin<Project> {
    // Fabric Loader requires at least Java 17.
    private static final int DEFAULT_JAVA_VER = 17;
    /**
     * The main class for the mod loader.
     */
    public static final String LOADER_MAIN_CLASS = "de.rhm176.loader.Main";

    /**
     * Applies the Silk plugin to the given Gradle project.
     *
     * @param project The project to apply the plugin to.
     */
    @Override
    public void apply(Project project) {
        Configuration equilinoxConfiguration = project.getConfigurations().create("equilinox", config -> {
            config.setDescription("The game JAR for Silk, e.g., EquilinoxWindows.jar.");
            config.setVisible(true);
            config.setCanBeConsumed(false);
            config.setCanBeResolved(true);
            config.setTransitive(false);
        });

        SilkExtension extension = project.getExtensions().create(
                "silk",
                SilkExtension.class
        );
        extension.initializeGameJarProvider(equilinoxConfiguration, project);
        extension.getRunDir().convention(project.getLayout().getProjectDirectory().dir("run"));

        project.getTasks().register("runGame", JavaExec.class, task -> {
            task.setGroup("Silk");
            task.setDescription("Runs the game.");

            File runDir = extension.getRunDir().getAsFile().get();
            task.setWorkingDir(runDir);

            TaskProvider<Jar> jarTaskProvider = project.getTasks().named("jar", Jar.class);
            Provider<RegularFile> modJarFileProvider = jarTaskProvider.flatMap(Jar::getArchiveFile);

            task.jvmArgs(
                    "-Dfabric.development=true",
                    "-Dfabric.gameJarPath=" + extension.getGameJar().get().getAsFile().toPath().toAbsolutePath()
            );

            JavaToolchainService javaToolchains = project.getExtensions().findByType(JavaToolchainService.class);
            if (javaToolchains != null) {
                String javaVersionPropertyName = "javaVersion";
                int defaultJavaVersion = DEFAULT_JAVA_VER;
                int targetJavaVersion = defaultJavaVersion;

                if (project.hasProperty(javaVersionPropertyName)) {
                    String propertyValue = project.property(javaVersionPropertyName).toString();
                    try {
                        targetJavaVersion = Integer.parseInt(propertyValue);
                        project.getLogger().info("runGame task: Using Java version {} from project property '{}'.", targetJavaVersion, javaVersionPropertyName);
                    } catch (NumberFormatException e) {
                        project.getLogger().warn(
                                "runGame task: Value '{}' for project property '{}' is not a valid integer. Using default Java version {}.",
                                propertyValue,
                                javaVersionPropertyName,
                                defaultJavaVersion
                        );
                    }
                } else {
                    project.getLogger().info(
                            "runGame task: Project property '{}' not found. Using default Java version {}.",
                            javaVersionPropertyName,
                            defaultJavaVersion
                    );
                }

                final int finalJavaVersion = targetJavaVersion;
                task.getJavaLauncher().set(javaToolchains.launcherFor(spec -> {
                    spec.getLanguageVersion().set(JavaLanguageVersion.of(finalJavaVersion));
                }));
            } else {
                project.getLogger().warn(
                        "runGame task: JavaToolchainService not found. " +
                                "Ensure a Java-related plugin (e.g., 'java' or 'java-library') is applied to the project. " +
                                "The task will use the default JVM."
                );
            }

            task.classpath(
                    extension.getGameJar(),
                    modJarFileProvider,
                    project.getConfigurations().getByName("runtimeClasspath")
            );

            task.dependsOn(jarTaskProvider, equilinoxConfiguration, project.getConfigurations().named("runtimeClasspath"));

            task.doFirst(t -> {
                if (!runDir.exists()) {
                    if (!runDir.mkdirs()) {
                        project.getLogger().warn("runGame task: Failed to create working directory: {}", runDir.getAbsolutePath());
                    }
                }

                if (!extension.getGameJar().isPresent()) {
                    throw new GradleException("Silk extension 'gameJar' must be set in the silk { ... } block.");
                }

                task.getMainClass().set(LOADER_MAIN_CLASS);
            });
        });

        project.afterEvaluate(p -> {
            boolean gameJarIsPresent = extension.getGameJar().isPresent();

            if (gameJarIsPresent) {
                File gameJarFile = extension.getGameJar().get().getAsFile();
                p.getDependencies().add(
                        "compileOnly",
                        p.files(gameJarFile.getAbsolutePath())
                );
            } else {
                project.getLogger().info("Silk plugin: 'gameJar' not configured in silk extension. Skipping dependency addition.");
            }
        });
    }
}