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
import de.rhm176.silk.extension.FabricExtension;
import de.rhm176.silk.task.ExtractNativesTask;
import de.rhm176.silk.task.GenerateFabricJsonTask;
import de.rhm176.silk.task.GenerateSourcesTask;
import de.rhm176.silk.task.TransformClassesTask;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.gradle.api.*;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.*;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.*;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.language.jvm.tasks.ProcessResources;
import org.jetbrains.annotations.NotNull;

/**
 * Main plugin class for Silk, a Gradle plugin to facilitate mod development for Equilinox.
 */
public class SilkPlugin implements Plugin<Project> {
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

        project.getConfigurations().named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, conf -> {
            conf.getDependencies().addLater(extension.getSilkLoaderCoordinates().map(coords -> {
                if (coords == null) return null;
                if (coords instanceof CharSequence && coords.toString().trim().isEmpty()) return null;
                return project.getDependencies().create(coords);
            }));
        });

        Provider<File> loaderJarFileProvider = extension
                .getSilkLoaderCoordinates()
                .flatMap(coords -> {
                    if (coords == null) {
                        project.getLogger()
                                .info("Silk: silkLoaderCoordinates is null. No loader JAR will be configured from it.");
                        return project.provider(() -> null);
                    }

                    if (coords instanceof CharSequence
                            && coords.toString().trim().isEmpty()) {
                        project.getLogger()
                                .info(
                                        "Silk: silkLoaderCoordinates is an empty or blank string. No loader JAR will be configured from it.");
                        return project.provider(() -> null);
                    }

                    Dependency dependency;
                    try {
                        dependency = project.getDependencies().create(coords);
                    } catch (Exception e) {
                        project.getLogger()
                                .warn(
                                        "Silk: Could not create a Gradle Dependency from silkLoaderCoordinates (value: '{}', type: {}). Error: {}. No loader JAR will be configured from it.",
                                        coords,
                                        coords.getClass().getName(),
                                        e.getMessage());
                        return project.provider(() -> null);
                    }

                    Configuration detachedConf = project.getConfigurations().detachedConfiguration(dependency);
                    detachedConf.setTransitive(false);
                    detachedConf.setDescription("Detached configuration for resolving the Silk Loader JAR.");

                    return detachedConf.getElements().map(resolvedFiles -> {
                        if (resolvedFiles.isEmpty()) {
                            project.getLogger()
                                    .warn(
                                            "Silk: Resolving silkLoaderCoordinates (original: '{}', as dependency: '{}') yielded no files.",
                                            coords,
                                            dependency);
                            return null;
                        }

                        for (FileSystemLocation location : resolvedFiles) {
                            File file = location.getAsFile();
                            if (file.isFile() && file.getName().toLowerCase().endsWith(".jar")) {
                                project.getLogger().debug("Silk: Resolved Silk Loader JAR: {}", file.getAbsolutePath());
                                return file;
                            }
                        }

                        project.getLogger()
                                .warn(
                                        "Silk: No .jar file found among resolved files for silkLoaderCoordinates (original: '{}', as dependency: '{}'). Resolved files: {}",
                                        coords,
                                        dependency,
                                        resolvedFiles.stream()
                                                .map(fsl -> fsl.getAsFile().getName())
                                                .collect(Collectors.toList()));
                        return null;
                    });
                });

        Provider<String> mainClassFromConfiguredLoaderManifest = project.provider(() -> {
            File loaderJarFile = loaderJarFileProvider.getOrNull();

            if (loaderJarFile == null || !loaderJarFile.exists()) {
                return null;
            }
            try (JarFile jar = new JarFile(loaderJarFile)) {
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    String mainClass = manifest.getMainAttributes().getValue(Attributes.Name.MAIN_CLASS);
                    if (mainClass != null && !mainClass.isEmpty()) {
                        return mainClass;
                    }
                }
            } catch (IOException e) {
                project.getLogger()
                        .warn(
                                "Silk: Could not read manifest from {}. Error: {}",
                                loaderJarFile.getName(),
                                e.getMessage());
            }
            return null;
        });

        Provider<String> effectiveLoaderMainClass =
                extension.getSilkLoaderMainClassOverride().orElse(mainClassFromConfiguredLoaderManifest);

        JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
        SourceSet mainSourceSet = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
        TaskProvider<ProcessResources> processResourcesTask =
                project.getTasks().named(mainSourceSet.getProcessResourcesTaskName(), ProcessResources.class);

        Provider<Directory> generatedFabricModJsonDir =
                project.getLayout().getBuildDirectory().dir("generated/silk/resources/" + mainSourceSet.getName());

        TaskProvider<GenerateFabricJsonTask> generateFabricModJson = project.getTasks()
                .register("generateFabricModJson", GenerateFabricJsonTask.class, task -> {
                    task.setDescription("Generates the fabric.mod.json file from silk.fabricManifest configuration.");
                    task.setGroup(null);

                    task.onlyIf(spec -> extension.getGenerateFabricModJson().getOrElse(false));

                    FabricExtension manifestConfig = extension.getFabric();
                    task.getId().set(manifestConfig.getId());
                    task.getVersion().set(manifestConfig.getVersion());
                    task.getModName().set(manifestConfig.getName());
                    task.getModDescription().set(manifestConfig.getDescription());
                    task.getAuthors().set(manifestConfig.getAuthors());
                    task.getContributors().set(manifestConfig.getContributors());
                    task.getLicenses().set(manifestConfig.getLicenses());
                    task.getContact().set(manifestConfig.getContact());
                    task.getJars().set(manifestConfig.getJars());
                    task.getLanguageAdapters().set(manifestConfig.getLanguageAdapters());
                    task.getMixins().set(manifestConfig.getMixins());
                    task.getDepends().set(manifestConfig.getDepends());
                    task.getRecommends().set(manifestConfig.getRecommends());
                    task.getSuggests().set(manifestConfig.getSuggests());
                    task.getConflicts().set(manifestConfig.getConflicts());
                    task.getBreaks().set(manifestConfig.getBreaks());
                    task.getAccessWidener().set(manifestConfig.getAccessWidener());
                    task.getIconFile().set(manifestConfig.getIconFile());
                    task.getIconSet().set(manifestConfig.getIconSet());
                    task.getEntrypointsContainer().set(manifestConfig.getEntrypoints());
                    task.getCustomData().set(manifestConfig.getCustomData());
                    task.getShouldVerify().set(extension.getVerifyFabricModJson());

                    task.getOutputFile().set(generatedFabricModJsonDir.map(d -> d.file("fabric.mod.json")));
                });
        processResourcesTask.configure(processResources -> processResources.dependsOn(generateFabricModJson));

        Configuration compileClasspath =
                project.getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);

        TaskProvider<TransformClassesTask> transformGameClassesTaskProvider = project.getTasks()
                .register("transformGameClasses", TransformClassesTask.class, task -> {
                    task.setGroup(null);

                    for (Project subProject : extension.getRegisteredSubprojectsInternal()) {
                        task.dependsOn(subProject.getTasksByName("modifyFabricModJson", false));
                    }
                    task.dependsOn(project.getTasksByName("modifyFabricModJson", false));

                    task.getInputJar().set(extension.getGameJar());

                    Provider<File> currentProjectModJsonProvider =
                            processResourcesTask.map(t -> new File(t.getDestinationDir(), "fabric.mod.json"));
                    task.getModConfigurationSources().from(currentProjectModJsonProvider);

                    for (Project subProject : extension.getRegisteredSubprojectsInternal()) {
                        subProject.getPlugins().withId("java", appliedJavaPlugin -> {
                            JavaPluginExtension subJavaExt =
                                    subProject.getExtensions().getByType(JavaPluginExtension.class);
                            SourceSet subMainSS = subJavaExt.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                            String subProcessResourcesTaskName = subMainSS.getProcessResourcesTaskName();

                            if (subProject.getTasks().getNames().contains(subProcessResourcesTaskName)) {
                                TaskProvider<ProcessResources> subProcessResourcesTask = subProject
                                        .getTasks()
                                        .named(subProcessResourcesTaskName, ProcessResources.class);

                                Provider<RegularFile> subModJsonProvider = subProject
                                        .getLayout()
                                        .file(subProcessResourcesTask.map(
                                                prTask -> new File(prTask.getDestinationDir(), "fabric.mod.json")));
                                Provider<File> subModJsonFileProvider = subModJsonProvider
                                        .map(RegularFile::getAsFile)
                                        .filter(File::exists);
                                if (subModJsonFileProvider.isPresent()) {
                                    task.getModConfigurationSources().from(subModJsonFileProvider);
                                }
                            } else {
                                project.getLogger()
                                        .warn(
                                                "Silk: Subproject {} does not have a '{}' task. Cannot find its fabric.mod.json for class transformation.",
                                                subProject.getPath(),
                                                subProcessResourcesTaskName);
                            }
                        });
                    }

                    Provider<Set<File>> dependencyJarsProvider = compileClasspath
                            .getIncoming()
                            .getArtifacts()
                            .getResolvedArtifacts()
                            .map(resolvedArtifactSet -> resolvedArtifactSet.stream()
                                    .map(ResolvedArtifactResult::getFile)
                                    .filter(file -> file.getName().endsWith(".jar") && file.isFile())
                                    .collect(Collectors.toSet()));
                    task.getModConfigurationSources().from(dependencyJarsProvider);

                    Provider<RegularFile> gameJarProvider = extension.getGameJar();
                    task.getOutputTransformedJar().set(gameJarProvider.flatMap(jar -> project.getLayout()
                            .getBuildDirectory()
                            .file("silk/transformed-jars/"
                                    + jar.getAsFile().getName().replace(".jar", "") + "-transformed.jar")));
                });

        project.getTasks()
                .named(mainSourceSet.getCompileJavaTaskName(), JavaCompile.class)
                .configure(t -> t.dependsOn(transformGameClassesTaskProvider));

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

            task.getInputJar()
                    .set(transformGameClassesTaskProvider.flatMap(TransformClassesTask::getOutputTransformedJar));
            task.getVineflowerClasspath().setFrom(vineflowerClasspath);
            task.getVineflowerArgs().set(extension.getVineflower().getArgs());

            Provider<RegularFile> gameJarProvider = extension.getGameJar();
            task.getOutputSourcesJar().set(gameJarProvider.flatMap(jar -> project.getLayout()
                    .getBuildDirectory()
                    .file("silk/sources/" + jar.getAsFile().getName().replace(".jar", "") + "-sources.jar")));
        });

        Provider<Directory> nativesDir = project.getLayout().getBuildDirectory().dir("silk/natives");
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

        TaskProvider<Task> modifyFabricModJsonTask = project.getTasks().register("modifyFabricModJson", task -> {
            task.setGroup(null);
            task.setDescription("Adds bundled submod JAR references to fabric.mod.json.");

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

                if (!fabricModJsonFile.exists() || bundledJarFileNames.isEmpty()) {
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
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
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

            task.dependsOn(transformGameClassesTaskProvider);

            task.setWorkingDir(extension.getRunDir().getAsFile());

            TaskProvider<Jar> jarTaskProvider = project.getTasks().named("jar", Jar.class);
            Provider<RegularFile> modJarFileProvider = jarTaskProvider.flatMap(Jar::getArchiveFile);
            Provider<File> gameJarProvider = transformGameClassesTaskProvider.flatMap(TransformClassesTask::getOutputTransformedJar).map(RegularFile::getAsFile);

            /*
            task.jvmArgs(
                    "-Dfabric.development=true",
                    "-Deqmodloader.loadedNatives=true",
                    "-Dfabric.gameJarPath=" + gameJarProvider.get().getAbsolutePath(),
                    nativesDir.map(d -> "-Djava.library.path=" + d.getAsFile().getAbsolutePath())
            );
            */

            task.classpath(
                    gameJarProvider,
                    modJarFileProvider,
                    project.getConfigurations().getByName(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));

            task.dependsOn(
                    jarTaskProvider,
                    extractNativesTaskProvider,
                    equilinoxConfiguration,
                    project.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));

            task.getMainClass().set(effectiveLoaderMainClass);

            task.doFirst(t -> {
                File runDir = extension.getRunDir().getAsFile().get();
                if (!runDir.exists()) {
                    if (!runDir.mkdirs()) {
                        t.getLogger().warn("Silk: Failed to create working directory: {}", runDir.getAbsolutePath());
                    }
                }

                task.jvmArgs(
                    "-Dfabric.development=true",
                    "-Deqmodloader.loadedNatives=true",
                    "-Dfabric.gameJarPath=" + gameJarProvider.get().getAbsolutePath(),
                    "-Djava.library.path=" + nativesDir.get().getAsFile().getAbsolutePath()
                );
            });
        });

        project.getDependencies()
                .addProvider(
                        JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME,
                        extension.getGameJar().flatMap(gameJarRegularFile -> {
                            File gameJarAsIoFile = gameJarRegularFile.getAsFile();
                            Provider<RegularFile> transformedGameJar = transformGameClassesTaskProvider.flatMap(
                                    TransformClassesTask::getOutputTransformedJar);
                            if (transformedGameJar.isPresent()
                                    && transformedGameJar.get().getAsFile().exists()) {
                                return project.provider(() -> project.files(
                                        transformedGameJar.get().getAsFile().getAbsolutePath()));
                            } else if (gameJarAsIoFile.exists()) {
                                return project.provider(() -> project.files(gameJarAsIoFile.getAbsolutePath()));
                            } else {
                                return project.provider(project::files);
                            }
                        }));

        project.afterEvaluate(evaluatedProject -> {
            SilkExtension currentExtension = evaluatedProject.getExtensions().getByType(SilkExtension.class);
            boolean isGenerationEnabled =
                    currentExtension.getGenerateFabricModJson().getOrElse(false);
            File manualFabricModJsonFile = evaluatedProject
                    .getLayout()
                    .getProjectDirectory()
                    .file("src/main/resources/fabric.mod.json") // TODO:
                    .getAsFile();

            SourceSet currentMainSourceSet = evaluatedProject
                    .getExtensions()
                    .getByType(JavaPluginExtension.class)
                    .getSourceSets()
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            if (isGenerationEnabled) {
                currentMainSourceSet.getResources().srcDir(generatedFabricModJsonDir);

                if (manualFabricModJsonFile.exists()) {
                    throw new GradleException(
                            "Silk Plugin: 'silk.generateFabricModJson' is true, but a 'src/main/resources/fabric.mod.json' "
                                    + "also exists. Please either remove the manual file or set 'generateFabricModJson = false'.");
                }
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
        boolean jitpackMavenExists = false;

        for (ArtifactRepository repo : project.getRepositories()) {
            if (repo instanceof MavenArtifactRepository mavenRepo) {
                String repoUrl = mavenRepo.getUrl().toString();
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
