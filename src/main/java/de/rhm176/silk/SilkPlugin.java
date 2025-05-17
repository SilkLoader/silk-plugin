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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.file.*;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jetbrains.annotations.NotNull;

/**
 * Main plugin class for Silk, a Gradle plugin to facilitate mod development for Equilinox.
 * <p>
 * This plugin performs several actions:
 * <ul>
 * <li>Applies the 'java' plugin.</li>
 * <li>Conditionally adds necessary Maven repositories.</li>
 * <li>Creates an {@code equilinox} configuration for specifying the game JAR.</li>
 * <li>Registers the {@link SilkExtension} under the name {@code silk} for user configuration,
 * which includes a nested {@link VineflowerExtension} for decompiler settings.</li>
 * <li>Creates a {@code vineflowerTool} configuration for the Vineflower decompiler dependency.</li>
 * <li>Registers a {@code genSources} task of type {@link GenerateSourcesTask} to decompile the game JAR.</li>
 * <li>Registers an {@code extractNatives} task of type {@link ExtractNativesTask} to extract native libraries from the game JAR.</li>
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
    private static final String JITPACK_MAVEN_URL = "https://jitpack.io";

    /**
     * Applies the Silk plugin to the given Gradle project.
     *
     * @param project The project to apply the plugin to.
     */
    @Override
    public void apply(@NotNull Project project) {
        project.getPluginManager().apply("java");

        conditionallyAddRepositories(project);

        Configuration equilinoxConfiguration = project.getConfigurations().create("equilinox", config -> {
            config.setDescription("The game JAR for Silk, e.g., EquilinoxWindows.jar.");
            config.setVisible(true);
            config.setCanBeConsumed(false);
            config.setCanBeResolved(true);
            config.setTransitive(false);
        });

        SilkExtension extension = project.getExtensions().create("silk", SilkExtension.class, project);
        extension.initializeGameJarProvider(equilinoxConfiguration, project);
        extension
                .getRunDir()
                .convention(project.getLayout().getProjectDirectory().dir("run"));

        Configuration vineflowerClasspath = project.getConfigurations().create("vineflowerTool", config -> {
            config.setDescription("Classpath for Vineflower decompiler tool.");
            config.setVisible(false);
            config.setCanBeConsumed(false);
            config.setCanBeResolved(true);
            config.setTransitive(true);

            Provider<Dependency> vineflowerDependency = extension
                    .getVineflower()
                    .getVersion()
                    .map(versionString ->
                            project.getDependencies().create("org.vineflower:vineflower:" + versionString));
            config.getDependencies().addLater(vineflowerDependency);
        });

        project.getTasks().register("genSources", GenerateSourcesTask.class, task -> {
            task.setGroup("Silk");
            task.setDescription("Decompiles the game JAR using Vineflower to produce a sources JAR.");

            task.getInputJar().value(extension.getGameJar());
            task.getVineflowerClasspath().setFrom(vineflowerClasspath);
            task.getVineflowerArgs().set(extension.getVineflower().getArgs());

            Provider<RegularFile> gameJarProvider = extension.getGameJar();
            task.getOutputSourcesJar().set(gameJarProvider.flatMap(jar -> project.getLayout()
                    .getBuildDirectory()
                    .file("silk-sources/" + jar.getAsFile().getName().replace(".jar", "") + "-sources.jar")));
        });

        Provider<Directory> nativesDir = project.getLayout().getBuildDirectory().dir("silk-natives");
        TaskProvider<ExtractNativesTask> extractNativesTaskProvider = project.getTasks()
                .register("extractNatives", ExtractNativesTask.class, task -> {
                    task.setGroup("Silk");
                    task.setDescription("Extracts native libraries from the game JAR.");
                    task.getInputJar().set(extension.getGameJar());
                    task.getNativesDir().set(nativesDir);
                });

        Provider<FileCollection> subprojectOutputFilesProvider = project.provider(() -> {
            List<Object> jarTaskOutputs = extension.getRegisteredSubprojectsInternal().stream()
                    .map(subProject -> {
                        try {
                            return subProject.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
                        } catch (UnknownTaskException e) {
                            project.getLogger()
                                    .warn(
                                            "Silk: Subproject '{}' does not have a 'jar' task of type Jar. Cannot bundle its output.",
                                            subProject.getPath());
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            return project.files(jarTaskOutputs);
        });

        TaskProvider<Task> modifyFabricModJsonTask = project.getTasks().register("silkModifyFabricModJson", task -> {
            task.setGroup(null);
            task.setDescription("Adds bundled submod JAR references to fabric.mod.json.");

            JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
            SourceSet mainSourceSet = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
            TaskProvider<ProcessResources> processResourcesTask =
                    project.getTasks().named(mainSourceSet.getProcessResourcesTaskName(), ProcessResources.class);

            DirectoryProperty resourcesOutputDir = project.getObjects().directoryProperty();
            resourcesOutputDir.set(
                    project.getLayout().dir(processResourcesTask.map(ProcessResources::getDestinationDir)));
            task.getInputs().dir(resourcesOutputDir).withPathSensitivity(PathSensitivity.RELATIVE);

            Provider<List<String>> bundledJarNamesProvider =
                    project.provider(() -> extension.getRegisteredSubprojectsInternal().stream()
                            .map(subProject -> {
                                try {
                                    return subProject
                                            .getTasks()
                                            .named("jar", Jar.class)
                                            .flatMap(Jar::getArchiveFileName)
                                            .getOrNull();
                                } catch (UnknownTaskException e) {
                                    project.getLogger()
                                            .warn(
                                                    "Silk: Subproject '{}' does not have a 'jar' task of type Jar. Cannot determine its archive name for fabric.mod.json.",
                                                    subProject.getPath());
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList()));
            task.getInputs().property("bundledJarNames", bundledJarNamesProvider);

            task.dependsOn(processResourcesTask);

            RegularFileProperty outputFabricModJsonFile = project.getObjects().fileProperty();
            outputFabricModJsonFile.set(resourcesOutputDir.file("fabric.mod.json"));
            task.getOutputs().file(outputFabricModJsonFile).withPropertyName("modifiedFabricModJson");

            task.doLast(t -> {
                File fabricModJsonFile = outputFabricModJsonFile.get().getAsFile();
                List<String> bundledJarFileNames = bundledJarNamesProvider.get();

                if (!fabricModJsonFile.exists()) {
                    t.getLogger()
                            .info(
                                    "Silk: fabric.mod.json not found at '{}'. Skipping modification.",
                                    fabricModJsonFile.getAbsolutePath());
                    return;
                }
                if (bundledJarFileNames.isEmpty()) {
                    t.getLogger()
                            .info("Silk: No submods registered for bundling. Skipping fabric.mod.json modification.");
                    return;
                }

                ObjectMapper objectMapper = new ObjectMapper();
                objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

                try {
                    JsonNode rootNode = objectMapper.readTree(fabricModJsonFile);
                    if (!rootNode.isObject()) {
                        t.getLogger()
                                .error(
                                        "Silk: fabric.mod.json content is not a JSON object. Path: {}",
                                        fabricModJsonFile.getAbsolutePath());
                        return;
                    }
                    ObjectNode objectNode = (ObjectNode) rootNode;

                    ArrayNode jarsArrayNode;
                    if (objectNode.has("jars")) {
                        JsonNode jarsField = objectNode.get("jars");
                        if (jarsField.isArray()) {
                            jarsArrayNode = (ArrayNode) jarsField;
                        } else {
                            t.getLogger()
                                    .warn(
                                            "Silk: 'jars' field in fabric.mod.json is present but not an array. It will be overwritten with a new array including bundled mods.");
                            jarsArrayNode = objectMapper.createArrayNode();
                            objectNode.set("jars", jarsArrayNode);
                        }
                    } else {
                        jarsArrayNode = objectMapper.createArrayNode();
                        objectNode.set("jars", jarsArrayNode);
                    }

                    Set<String> existingJarPaths = new HashSet<>();
                    for (JsonNode entry : jarsArrayNode) {
                        if (entry.isObject()
                                && entry.has("file")
                                && entry.get("file").isTextual()) {
                            existingJarPaths.add(entry.get("file").asText());
                        }
                    }

                    int newEntriesAdded = 0;
                    for (String bundledJarName : bundledJarFileNames) {
                        String jarPathInMetaInf = "META-INF/jars/" + bundledJarName;
                        if (!existingJarPaths.contains(jarPathInMetaInf)) {
                            ObjectNode newJarEntry = objectMapper.createObjectNode();
                            newJarEntry.put("file", jarPathInMetaInf);
                            jarsArrayNode.add(newJarEntry);
                            newEntriesAdded++;
                        }
                    }

                    if (newEntriesAdded > 0) {
                        File tempFile = Files.createTempFile(
                                        fabricModJsonFile.getParentFile().toPath(), "fabric.mod.json", ".tmp")
                                .toFile();
                        objectMapper.writeValue(tempFile, objectNode);
                        Files.move(
                                tempFile.toPath(),
                                fabricModJsonFile.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                        t.getLogger()
                                .lifecycle(
                                        "Silk: Updated fabric.mod.json at '{}' with {} new bundled mod entries.",
                                        fabricModJsonFile.getAbsolutePath(),
                                        newEntriesAdded);
                    } else {
                        t.getLogger()
                                .info(
                                        "Silk: All registered bundled mods are already present in fabric.mod.json or no new mods to add.");
                    }

                } catch (IOException e) {
                    throw new GradleException(
                            "Silk: Failed to read or write fabric.mod.json at " + fabricModJsonFile.getAbsolutePath(),
                            e);
                }
            });
        });

        project.getTasks().named("jar", Jar.class, jarTask -> {
            jarTask.dependsOn(modifyFabricModJsonTask);

            jarTask.from(subprojectOutputFilesProvider, copySpec -> copySpec.into("META-INF/jars/"));
        });

        project.getTasks().register("runGame", JavaExec.class, task -> {
            task.setGroup("Silk");
            task.setDescription("Runs the game.");

            File runDir = extension.getRunDir().getAsFile().get();
            task.setWorkingDir(runDir);

            TaskProvider<Jar> jarTaskProvider = project.getTasks().named("jar", Jar.class);
            Provider<RegularFile> modJarFileProvider = jarTaskProvider.flatMap(Jar::getArchiveFile);

            task.jvmArgs(
                    "-Dfabric.development=true",
                    "-Deqmodloader.loadedNatives=true",
                    "-Dfabric.gameJarPath="
                            + extension.getGameJar().get().getAsFile().toPath().toAbsolutePath(),
                    "-Djava.library.path="
                            + nativesDir.get().getAsFile().toPath().toAbsolutePath());

            JavaToolchainService javaToolchains = project.getExtensions().findByType(JavaToolchainService.class);
            assert javaToolchains != null; // stupid ide, this is impossible since silk applies `java`.

            String javaVersionPropertyName = "javaVersion";
            int defaultJavaVersion = DEFAULT_JAVA_VER;
            int targetJavaVersion = defaultJavaVersion;

            if (project.hasProperty(javaVersionPropertyName)) {
                String propertyValue = Optional.ofNullable(project.property(javaVersionPropertyName))
                        .map(Object::toString)
                        .orElse("N/A");
                try {
                    targetJavaVersion = Integer.parseInt(propertyValue);
                    task.getLogger()
                            .info(
                                    "runGame task: Using Java version {} from project property '{}'.",
                                    targetJavaVersion,
                                    javaVersionPropertyName);
                } catch (NumberFormatException e) {
                    task.getLogger()
                            .warn(
                                    "runGame task: Value '{}' for project property '{}' is not a valid integer. Using default Java version {}.",
                                    propertyValue,
                                    javaVersionPropertyName,
                                    defaultJavaVersion);
                }
            } else {
                task.getLogger()
                        .info(
                                "runGame task: Project property '{}' not found. Using default Java version {}.",
                                javaVersionPropertyName,
                                defaultJavaVersion);
            }

            final int finalJavaVersion = targetJavaVersion;
            task.getJavaLauncher().set(javaToolchains.launcherFor(spec -> spec.getLanguageVersion()
                    .set(JavaLanguageVersion.of(finalJavaVersion))));

            task.classpath(
                    extension.getGameJar(),
                    modJarFileProvider,
                    project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));

            task.dependsOn(
                    jarTaskProvider,
                    extractNativesTaskProvider,
                    equilinoxConfiguration,
                    project.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));

            task.doFirst(t -> {
                if (!runDir.exists()) {
                    if (!runDir.mkdirs()) {
                        t.getLogger()
                                .warn("runGame task: Failed to create working directory: {}", runDir.getAbsolutePath());
                    }
                }

                if (!extension.getGameJar().isPresent()) {
                    throw new GradleException("Silk extension 'gameJar' must be set in the silk { ... } block.");
                }

                task.getMainClass().set(LOADER_MAIN_CLASS);
            });
        });

        project.getDependencies().addProvider(
                JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                extension.getGameJar().flatMap(gameJarRegularFile -> {
                    File gameJarAsIoFile = gameJarRegularFile.getAsFile();
                    if (gameJarAsIoFile.exists()) {
                        return project.provider(() -> project.files(gameJarAsIoFile.getAbsolutePath()));
                    } else {
                        project.getLogger().warn(
                                "Silk: The configured 'gameJar' points to a non-existent file: '{}'. " +
                                        "It will not be added to compileOnly dependencies.",
                                gameJarAsIoFile.getAbsolutePath()
                        );
                        return project.provider(project::files);
                    }
                })
        );
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
        boolean jitpackMavenExists = false;

        for (ArtifactRepository repo : project.getRepositories()) {
            if (repo instanceof MavenArtifactRepository) {
                String repoUrl = ((MavenArtifactRepository) repo).getUrl().toString();
                if (repoUrl.endsWith("/")) {
                    repoUrl = repoUrl.substring(0, repoUrl.length() - 1);
                }

                if (MAVEN_CENTRAL_URL1.equals(repoUrl) || MAVEN_CENTRAL_URL2.equals(repoUrl)) {
                    mavenCentralExists = true;
                }
                if (JITPACK_MAVEN_URL.equals(repoUrl)) {
                    jitpackMavenExists = true;
                }
                if (FABRIC_MAVEN_URL.regionMatches(true, 0, repoUrl, 0, FABRIC_MAVEN_URL.length() - 1)) {
                    fabricMavenExists = true;
                }
            }
        }

        if (!mavenCentralExists) {
            project.getRepositories().mavenCentral();
        }

        if (!jitpackMavenExists) {
            project.getRepositories().maven(repo -> {
                repo.setUrl(URI.create(JITPACK_MAVEN_URL));
                repo.setName("JitPack");
            });
        }

        if (!fabricMavenExists) {
            project.getRepositories().maven(repo -> {
                repo.setUrl(URI.create(FABRIC_MAVEN_URL));
                repo.setName("FabricMC");
            });
        }
    }
}
