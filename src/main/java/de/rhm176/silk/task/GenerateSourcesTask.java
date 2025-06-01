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
package de.rhm176.silk.task;

import de.rhm176.silk.SilkExtension;
import de.rhm176.silk.SilkPlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import org.gradle.api.AntBuilder;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.*;
import org.gradle.process.ExecOperations;
import org.jetbrains.annotations.NotNull;

/**
 * A Gradle task that decompiles a given game JAR using the Vineflower decompiler
 * and packages the resulting source files into a sources JAR.
 * <p>
 * This task is typically configured by the {@link SilkPlugin} and uses
 * properties defined in the {@link SilkExtension}.
 */
public abstract class GenerateSourcesTask extends DefaultTask {
    private final ExecOperations execOperations;

    /**
     * The input game JAR file to be decompiled.
     * This property must be set before the task executes.
     *
     * @return A {@link RegularFileProperty} representing the input JAR.
     */
    @InputFile
    public abstract RegularFileProperty getInputJar();

    /**
     * The output JAR file where the decompiled sources will be stored.
     * This property must be set before the task executes.
     *
     * @return A {@link RegularFileProperty} representing the output sources JAR.
     */
    @OutputFile
    public abstract RegularFileProperty getOutputSourcesJar();

    /**
     * The classpath containing the Vineflower decompiler and its dependencies.
     * This property must be configured with the Vineflower JAR.
     *
     * @return A {@link ConfigurableFileCollection} for the Vineflower classpath.
     */
    @InputFiles
    @Classpath
    public abstract ConfigurableFileCollection getVineflowerClasspath();

    /**
     * A list of user-supplied arguments to be passed to the Vineflower decompiler.
     * <p>
     * These arguments are added to a set of default arguments defined within the
     * {@link #constructVineflowerArgs(File, File, List)} method before being
     * passed to Vineflower. The final command will also include the input JAR path
     * and output directory path.
     * <p>
     * This property is typically configured by the {@link SilkPlugin} based on
     * settings in the {@code silk.vineflower.args} block of the build script.
     *
     * @return A {@link ListProperty} for user-supplied Vineflower arguments.
     */
    @Input
    public abstract ListProperty<String> getVineflowerArgs();

    /**
     * Constructs a new GenerateSourcesTask.
     * Gradle's dependency injection mechanism provides the {@link ExecOperations} service.
     *
     * @param execOperations Service for executing external processes, used here to run Vineflower.
     */
    @Inject
    public GenerateSourcesTask(ExecOperations execOperations) {
        this.execOperations = execOperations;
    }

    /**
     * The main action of the task. It performs the following steps:
     * <ol>
     * <li>Validates the existence of the input game JAR.</li>
     * <li>Creates a temporary directory for decompiled source files.</li>
     * <li>Executes the Vineflower decompiler as a Java process.</li>
     * <li>Archives the decompiled sources from the temporary directory into the output sources JAR.</li>
     * <li>Cleans up the temporary directory.</li>
     * </ol>
     *
     * @throws GradleException if the input JAR does not exist, if temporary directories cannot be created,
     * or if Vineflower decompilation fails.
     */
    @TaskAction
    public void generateSources() {
        Project project = getProject();
        File gameJarFile = getInputJar().get().getAsFile();
        File sourcesJarFile = getOutputSourcesJar().get().getAsFile();

        if (!gameJarFile.exists()) {
            throw new GradleException("Input gameJar does not exist: " + gameJarFile.getAbsolutePath());
        }

        File tempDecompiledDir = new File(getTemporaryDir(), "decompiled-sources");
        if (tempDecompiledDir.exists()) {
            project.delete(tempDecompiledDir);
        }
        if (!tempDecompiledDir.mkdirs()) {
            throw new GradleException("Could not create temporary directory for decompiled sources: "
                    + tempDecompiledDir.getAbsolutePath());
        }

        List<String> vineflowerArgs = constructVineflowerArgs(
                gameJarFile, tempDecompiledDir, getVineflowerArgs().get());

        try {
            execOperations
                    .javaexec(spec -> {
                        spec.setClasspath(getVineflowerClasspath());
                        spec.getMainClass().set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler");
                        spec.setArgs(vineflowerArgs);
                        spec.setErrorOutput(System.err);
                        spec.setStandardOutput(System.out);
                    })
                    .assertNormalExitValue();
        } catch (Exception e) {
            throw new GradleException("Vineflower decompilation failed for " + gameJarFile.getName(), e);
        }

        if (sourcesJarFile.exists()) {
            project.delete(sourcesJarFile);
        }
        if (sourcesJarFile.getParentFile() != null) {
            sourcesJarFile.getParentFile().mkdirs();
        }

        AntBuilder ant = project.getAnt();
        ant.invokeMethod("jar", new Object[] {
            Map.of(
                    "destfile", sourcesJarFile.getAbsolutePath(),
                    "basedir", tempDecompiledDir.getAbsolutePath(),
                    "compress", Boolean.TRUE)
        });
    }

    /**
     * Extracts the key from a command-line argument.
     * For arguments like "-key=value", it returns "-key".
     * For simple flags like "-flag", it returns "-flag".
     *
     * @param arg The command-line argument.
     * @return The key of the argument.
     */
    private static String getArgKey(String arg) {
        if (arg == null) {
            return "";
        }
        int equalsIndex = arg.indexOf('=');
        if (equalsIndex > 0) {
            return arg.substring(0, equalsIndex);
        }
        return arg;
    }

    /**
     * Constructs the final list of command-line arguments for the Vineflower decompiler.
     * It intelligently merges a predefined set of default arguments with user-supplied arguments,
     * where user-supplied arguments take precedence for the same flag key.
     * Finally, it appends the input JAR path and output directory path.
     *
     * @param gameJarFile The game JAR file to be decompiled.
     * @param tempDecompiledDir The directory where Vineflower should output the decompiled source files.
     * @param userProvidedArgs A list of arguments provided by the user via the {@link #getVineflowerArgs()} property.
     * @return A {@link NotNull} list of strings representing the complete arguments for Vineflower.
     */
    private static @NotNull List<String> constructVineflowerArgs(
            File gameJarFile, File tempDecompiledDir, List<String> userProvidedArgs) {
        Map<String, String> mergedArgs = new LinkedHashMap<>();

        List<String> defaultArgs = new ArrayList<>();
        defaultArgs.add("-log=WARN");

        for (String arg : defaultArgs) {
            mergedArgs.put(getArgKey(arg), arg);
        }

        if (userProvidedArgs != null) {
            for (String userArg : userProvidedArgs) {
                if (userArg != null && !userArg.trim().isEmpty()) {
                    mergedArgs.put(getArgKey(userArg), userArg);
                }
            }
        }

        List<String> finalArgs = new ArrayList<>(mergedArgs.values());

        finalArgs.add(gameJarFile.getAbsolutePath());
        finalArgs.add(tempDecompiledDir.getAbsolutePath());

        return finalArgs;
    }
}
