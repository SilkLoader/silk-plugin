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
import de.rhm176.silk.task.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
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
    interface SilkRootMarker {}

    public static final String SILK_TASK_COMMON_GROUP = "Silk";

    public static final String MODIFY_FABRIC_MOD_JSON_TASK_NAME = "modifyFabricModJson";
    public static final String TRANSFORM_CLASSES_TASK_NAME = "transformGameClasses";
    public static final String EXTRACT_NATIVES_TASK_NAME = "extractNatives";
    public static final String GENERATE_FABRIC_MOD_JSON_TASK_NAME = "generateFabricModJson";
    public static final String GENERATE_SOURCES_TASK_NAME = "genSources";

    public static final String DEFAULT_RUN_CONFIG_NAME = "game";
    public static final String DEFAULT_RUN_GAME_TASK_NAME = "runGame";

    public static final String EQUILINOX_CONFIGURATION_NAME = "equilinox";

    private static final String FABRIC_MAVEN_URL = "https://maven.fabricmc.net/";
    private static final String RHM_MAVEN_URL = "https://maven.rhm176.de/releases";

    private static boolean isRootSilkProject(Project project) {
        return project == project.getRootProject()
                || project.getRootProject().getExtensions().findByType(SilkRootMarker.class) == null;
    }

    /**
     * Applies the Silk plugin to the given Gradle project.
     *
     * @param project The project to apply the plugin to.
     */
    @Override
    public void apply(@NotNull Project project) {
        project.getPluginManager().apply("java-library");

        if (project == project.getRootProject()) {
            project.getExtensions().create("silkRootMarker", SilkRootMarker.class);
        }

        conditionallyAddRepositories(project);

        Configuration equilinoxConfiguration = project.getConfigurations()
                .create(EQUILINOX_CONFIGURATION_NAME, config -> {
                    config.setDescription("The game JAR for Silk, e.g., EquilinoxWindows.jar.");
                    config.setVisible(true);
                    config.setCanBeConsumed(false);
                    config.setCanBeResolved(true);
                    config.setTransitive(false);
                });

        SilkExtension extension = project.getExtensions().create("silk", SilkExtension.class, project);
        extension.initializeGameJarProvider(equilinoxConfiguration, project);

        project.getConfigurations().named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, conf -> {
            conf.getDependencies().addLater(extension.getSilkLoaderCoordinates().map(coords -> {
                if (coords instanceof CharSequence && coords.toString().trim().isEmpty()) return null;
                return project.getDependencies().create(coords);
            }));
        });

        Provider<File> loaderJarFileProvider = extension
                .getSilkLoaderCoordinates()
                .flatMap(coords -> {
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
                                                .map(FileSystemLocation::getAsFile)
                                                .map(File::getName)
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
                .register(GENERATE_FABRIC_MOD_JSON_TASK_NAME, GenerateFabricJsonTask.class, task -> {
                    task.setDescription("Generates the fabric.mod.json file from silk.fabric configuration.");
                    task.setGroup(SILK_TASK_COMMON_GROUP);

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

                    task.getMainResourceDirectories()
                            .from(mainSourceSet.getResources().getSrcDirs());

                    task.getOutputFile().set(generatedFabricModJsonDir.map(d -> d.file("fabric.mod.json")));
                });
        processResourcesTask.configure(processResources -> {
            processResources.dependsOn(generateFabricModJson);

            Provider<RegularFile> fabricModJsonOutput =
                    generateFabricModJson.flatMap(GenerateFabricJsonTask::getOutputFile);
            processResources.from(fabricModJsonOutput);
        });

        Configuration compileClasspath =
                project.getConfigurations().getByName(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME);

        TaskProvider<ModifyFabricModJsonTask> modifyFabricModJsonTask = project.getTasks()
                .register(MODIFY_FABRIC_MOD_JSON_TASK_NAME, ModifyFabricModJsonTask.class, task -> {
                    task.setGroup(SILK_TASK_COMMON_GROUP);
                    task.setDescription("Adds bundled submod JAR references to fabric.mod.json.");

                    Provider<RegularFile> fabricModJsonFileLocation = project.getLayout()
                            .file(processResourcesTask.map(
                                    prTask -> new File(prTask.getDestinationDir(), "fabric.mod.json")));

                    task.getInputJsonFile().set(fabricModJsonFileLocation);
                    task.getOutputJsonFile().set(fabricModJsonFileLocation);

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
                                                            "Silk: Subproject '{}' does not have a 'jar' task of type Jar. "
                                                                    + "Cannot determine its archive name for fabric.mod.json.",
                                                            subProject.getPath());
                                            return null;
                                        }
                                    })
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toList()));
                    task.getBundledJarNames().set(bundledJarNamesProvider);

                    task.dependsOn(processResourcesTask);
                });

        TaskProvider<TransformClassesTask> transformGameClassesTaskProvider = project.getTasks()
                .register(TRANSFORM_CLASSES_TASK_NAME, TransformClassesTask.class, task -> {
                    task.setGroup(SILK_TASK_COMMON_GROUP);

                    if (isRootSilkProject(project)) {
                        for (Project subProject : extension.getRegisteredSubprojectsInternal()) {
                            task.dependsOn(subProject.getTasksByName(MODIFY_FABRIC_MOD_JSON_TASK_NAME, false));
                        }
                        task.dependsOn(modifyFabricModJsonTask);

                        task.getInputJar().set(extension.getGameJar());

                        Provider<File> currentProjectModJsonProvider =
                                processResourcesTask.map(t -> new File(t.getDestinationDir(), "fabric.mod.json"));
                        task.getModConfigurationSources().from(currentProjectModJsonProvider);

                        for (Project subProject : extension.getRegisteredSubprojectsInternal()) {
                            subProject.getPlugins().withId("java", appliedJavaPlugin -> {
                                JavaPluginExtension subJavaExt =
                                        subProject.getExtensions().getByType(JavaPluginExtension.class);
                                SourceSet subMainSS =
                                        subJavaExt.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);
                                String subProcessResourcesTaskName = subMainSS.getProcessResourcesTaskName();

                                if (subProject.getTasks().getNames().contains(subProcessResourcesTaskName)) {
                                    TaskProvider<ProcessResources> subProcessResourcesTask = subProject
                                            .getTasks()
                                            .named(subProcessResourcesTaskName, ProcessResources.class);

                                    Provider<RegularFile> subModJsonProvider = subProject
                                            .getLayout()
                                            .file(subProcessResourcesTask.map(
                                                    prTask -> new File(prTask.getDestinationDir(), "fabric.mod.json")));
                                    task.getModConfigurationSources().from(subModJsonProvider);
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
                    } else {
                        task.setEnabled(false);
                        task.getOutputTransformedJar()
                                .set(project.getRootProject()
                                        .getTasks()
                                        .named(TRANSFORM_CLASSES_TASK_NAME, TransformClassesTask.class)
                                        .flatMap(TransformClassesTask::getOutputTransformedJar));
                    }
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

        if (isRootSilkProject(project)) {
            project.getTasks().register(GENERATE_SOURCES_TASK_NAME, GenerateSourcesTask.class, task -> {
                task.setGroup(SILK_TASK_COMMON_GROUP);
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

            Provider<Directory> nativesDir =
                    project.getLayout().getBuildDirectory().dir("silk/natives");
            TaskProvider<ExtractNativesTask> extractNativesTaskProvider = project.getTasks()
                    .register(EXTRACT_NATIVES_TASK_NAME, ExtractNativesTask.class, task -> {
                        task.setGroup(SILK_TASK_COMMON_GROUP);
                        task.setDescription("Extracts native libraries from the game JAR.");
                        task.getInputJar().set(extension.getGameJar());
                        task.getNativesDir().set(nativesDir);
                    });

            extension.getRuns().all((runConfig) -> {
                String taskName = "run"
                        + (runConfig.getName().substring(0, 1).toUpperCase(Locale.ROOT)
                                + runConfig.getName().substring(1));
                project.getTasks().register(taskName, JavaExec.class, task -> {
                    task.setGroup(SILK_TASK_COMMON_GROUP);
                    task.setDescription("Runs the game with the '" + runConfig.getName() + "' configuration.");

                    SourceSet sourceSet = runConfig.getSourceSet().get();
                    TaskProvider<Jar> jarTaskProvider = project.getTasks().named(sourceSet.getJarTaskName(), Jar.class);
                    Provider<RegularFile> modJarFileProvider = jarTaskProvider.flatMap(Jar::getArchiveFile);
                    Provider<File> gameJarProvider = transformGameClassesTaskProvider
                            .flatMap(TransformClassesTask::getOutputTransformedJar)
                            .map(RegularFile::getAsFile);

                    task.dependsOn(extractNativesTaskProvider, transformGameClassesTaskProvider, jarTaskProvider);

                    task.onlyIf(t -> {
                        String mainClass = effectiveLoaderMainClass.getOrNull();
                        if (mainClass == null || mainClass.trim().isEmpty()) {
                            t.getLogger()
                                    .warn(
                                            "Silk: '{}' task is unavailable because Silk Loader main class could not be determined. "
                                                    + "Please ensure 'silk.silkLoaderCoordinates' is set correctly and resolves to a JAR with a Main-Class, "
                                                    + "or provide 'silk.silkLoaderMainClassOverride'.",
                                            taskName);
                            return false;
                        }

                        if (!gameJarProvider.isPresent()
                                || !gameJarProvider.get().exists()) {
                            t.getLogger()
                                    .warn(
                                            "Silk: '{}' task is unavailable because the transformed game JAR file does not exist at the expected location: {}. "
                                                    + "Please ensure the '" + TRANSFORM_CLASSES_TASK_NAME
                                                    + "' task completed successfully and created this output.",
                                            taskName,
                                            gameJarProvider.get().getAbsolutePath());
                            return false;
                        }

                        return true;
                    });

                    task.workingDir(runConfig.getRunDir());

                    task.classpath(
                            gameJarProvider,
                            modJarFileProvider,
                            project.getConfigurations().getByName(sourceSet.getRuntimeClasspathConfigurationName()));

                    task.getMainClass().set(effectiveLoaderMainClass);

                    task.args(runConfig.getProgramArgs());
                    task.getJvmArgumentProviders().add(() -> {
                        Map<String, String> properties = new LinkedHashMap<>();
                        List<String> otherArgs = new ArrayList<>();

                        runConfig
                                .getJvmArgs()
                                .getOrElse(Collections.emptyList())
                                .forEach(arg -> {
                                    if (arg.startsWith("-D")) {
                                        String[] parts = arg.substring(2).split("=", 2);
                                        if (parts.length > 0 && !parts[0].isEmpty()) {
                                            String key = parts[0];
                                            String value = parts.length > 1 ? parts[1] : "";
                                            properties.put(key, value);
                                        }
                                    } else {
                                        otherArgs.add(arg);
                                    }
                                });

                        properties.put("fabric.development", "true");
                        properties.put("eqmodloader.loadedNatives", "true");
                        properties.put(
                                "fabric.gameJarPath", gameJarProvider.get().getAbsolutePath());
                        properties.put(
                                "java.library.path",
                                nativesDir.get().getAsFile().getAbsolutePath()
                                        + File.pathSeparator
                                        + gameJarProvider.get().getParentFile().getAbsolutePath());

                        List<String> finalJvmArgs = new ArrayList<>(otherArgs);

                        properties.forEach(
                                (key, value) -> finalJvmArgs.add("-D" + key + (value.isEmpty() ? "" : "=" + value)));

                        return finalJvmArgs;
                    });
                    task.environment(runConfig.getEnvironmentVariables().get());

                    task.doFirst(t -> {
                        File runDir = runConfig.getRunDir().getAsFile().get();
                        if (!runDir.exists()) {
                            if (!runDir.mkdirs()) {
                                t.getLogger()
                                        .warn("Silk: Failed to create working directory: {}", runDir.getAbsolutePath());
                            }
                        }
                    });
                });
            });
        }

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

        project.getTasks()
                .withType(Jar.class)
                .matching(jarTask -> jarTask.getName().equals("sourcesJar"))
                .configureEach(sourcesJarTask -> {
                    sourcesJarTask.dependsOn(generateFabricModJson);
                    sourcesJarTask.dependsOn(modifyFabricModJsonTask);
                });
        project.getTasks()
                .withType(Jar.class)
                .matching(jarTask -> jarTask.getName().equals("javadocJar"))
                .configureEach(javadocJarTask -> {
                    javadocJarTask.dependsOn(generateFabricModJson);
                    javadocJarTask.dependsOn(modifyFabricModJsonTask);
                });

        project.getTasks().named("jar", Jar.class, jarTask -> {
            jarTask.dependsOn(modifyFabricModJsonTask);

            jarTask.from(subprojectOutputFilesProvider, copySpec -> copySpec.into("META-INF/jars/"));
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
            extension.getRuns().maybeCreate(DEFAULT_RUN_CONFIG_NAME);

            extension.getRegisteredSubprojectsInternal().forEach(subProject -> evaluatedProject
                    .getDependencies()
                    .add(JavaPlugin.COMPILE_ONLY_API_CONFIGURATION_NAME, subProject));

            boolean isGenerationEnabled = extension.getGenerateFabricModJson().getOrElse(false);
            SourceSet currentMainSourceSet = evaluatedProject
                    .getExtensions()
                    .getByType(JavaPluginExtension.class)
                    .getSourceSets()
                    .getByName(SourceSet.MAIN_SOURCE_SET_NAME);

            File mainResourcesDir =
                    currentMainSourceSet.getResources().getSrcDirs().iterator().next();
            File manualFabricModJsonFile = new File(mainResourcesDir, "fabric.mod.json");

            if (isGenerationEnabled && manualFabricModJsonFile.exists()) {
                throw new GradleException("Silk Plugin: 'silk.generateFabricModJson' is true, but '"
                        + manualFabricModJsonFile.getPath() + "' "
                        + "also exists. Please either remove the manual file or set 'generateFabricModJson = false'.");
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
        boolean fabricMavenExists = false;
        boolean rhmMavenExists = false;

        for (ArtifactRepository repo : project.getRepositories()) {
            if (repo instanceof MavenArtifactRepository mavenRepo) {
                String repoUrl = mavenRepo.getUrl().toString();
                if (repoUrl.endsWith("/")) {
                    repoUrl = repoUrl.substring(0, repoUrl.length() - 1);
                }

                if (RHM_MAVEN_URL.regionMatches(true, 0, repoUrl, 0, RHM_MAVEN_URL.length() - 1)) {
                    rhmMavenExists = true;
                }
                if (FABRIC_MAVEN_URL.regionMatches(true, 0, repoUrl, 0, FABRIC_MAVEN_URL.length() - 1)) {
                    fabricMavenExists = true;
                }
            }
        }

        project.getRepositories().mavenCentral();

        if (!rhmMavenExists) {
            project.getRepositories().maven(repo -> {
                repo.setUrl(URI.create(RHM_MAVEN_URL));
                repo.setName("RHM's Maven");
            });
        }
        if (!fabricMavenExists) {
            project.getRepositories().maven(repo -> {
                repo.setUrl(URI.create(FABRIC_MAVEN_URL));
                repo.setName("FabricMC Maven");
            });
        }
    }
}
