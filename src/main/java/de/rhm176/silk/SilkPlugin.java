package de.rhm176.silk;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.net.URI;

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

    private static final String FABRIC_MAVEN_URL = "https://maven.fabricmc.net/";
    private static final String MAVEN_CENTRAL_URL1 = "https://repo.maven.apache.org/maven2";
    private static final String MAVEN_CENTRAL_URL2 = "https://repo1.maven.org/maven2";

    /**
     * Applies the Silk plugin to the given Gradle project.
     *
     * @param project The project to apply the plugin to.
     */
    @Override
    public void apply(@NotNull Project project) {
        conditionallyAddRepositories(project);

        Configuration equilinoxConfiguration = project.getConfigurations().create("equilinox", config -> {
            config.setDescription("The game JAR for Silk, e.g., EquilinoxWindows.jar.");
            config.setVisible(true);
            config.setCanBeConsumed(false);
            config.setCanBeResolved(true);
            config.setTransitive(false);
        });

        SilkExtension extension = project.getExtensions().create(
                "silk",
                SilkExtension.class,
                project
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

    /**
     * Conditionally adds Maven Central and FabricMC repositories to the project
     * if they are not already present.
     *
     * @param project The project to add repositories to.
     */
    private void conditionallyAddRepositories(Project project) {
        boolean mavenCentralExists = false;
        boolean fabricMavenExists = false;

        for (ArtifactRepository repo : project.getRepositories()) {
            if (repo instanceof MavenArtifactRepository) {
                String repoUrl = ((MavenArtifactRepository) repo).getUrl().toString();
                if (repoUrl.endsWith("/")) {
                    repoUrl = repoUrl.substring(0, repoUrl.length() - 1);
                }

                if (MAVEN_CENTRAL_URL1.equals(repoUrl) || MAVEN_CENTRAL_URL2.equals(repoUrl)) {
                    mavenCentralExists = true;
                }
                if (FABRIC_MAVEN_URL.regionMatches(true, 0, repoUrl, 0, FABRIC_MAVEN_URL.length() -1 )) {
                    fabricMavenExists = true;
                }
            }
            if (mavenCentralExists && fabricMavenExists) {
                break;
            }
        }

        if (!mavenCentralExists) {
            project.getRepositories().mavenCentral();
        }

        if (!fabricMavenExists) {
            project.getRepositories().maven(repo -> {
                repo.setUrl(URI.create(FABRIC_MAVEN_URL));
                repo.setName("FabricMC");
            });
        }
    }
}