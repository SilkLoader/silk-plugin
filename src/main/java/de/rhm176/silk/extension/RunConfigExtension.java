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
package de.rhm176.silk.extension;

import java.util.*;
import javax.inject.Inject;
import org.gradle.api.Named;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.SourceSet;

/**
 * Defines a single, named configuration for launching a Java application.
 * <p>
 * This class is not instantiated directly. Instead, configurations are created
 * within the {@code runs} block of the {@code silk} extension in a Gradle build script.
 */
public abstract class RunConfigExtension implements Named {
    private final Project project;

    /**
     * The working directory for the process.
     * @return A {@link DirectoryProperty} for the working directory.
     */
    public abstract DirectoryProperty getRunDir();
    /**
     * The JVM arguments to use when launching the process.
     * @return A {@link ListProperty} for the JVM arguments.
     */
    public abstract ListProperty<String> getJvmArgs();
    /**
     * The program arguments to pass to the main class.
     * @return A {@link ListProperty} for the program arguments.
     */
    public abstract ListProperty<String> getProgramArgs();
    /**
     * The environment variables to set for the process.
     * @return A {@link MapProperty} for the environment variables.
     */
    public abstract MapProperty<String, Object> getEnvironmentVariables();
    /**
     * The source set whose output will be used in the classpath. Defaults to the 'main' source set.
     * @return A {@link Property} for the source set.
     */
    public abstract Property<SourceSet> getSourceSet();

    /**
     * Injects dependencies required by this extension. Meant to be called by Gradle's {@link ObjectFactory}.
     * @param project The current {@link Project}.
     */
    @Inject
    public RunConfigExtension(Project project) {
        this.project = project;

        this.getRunDir().convention(project.getLayout().getProjectDirectory().dir("run"));
        this.getSourceSet().convention(project.provider(() -> project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(SourceSet.MAIN_SOURCE_SET_NAME)));
    }

    /**
     * Specifies the SourceSet to use for this run configuration.
     * @param sourceSet The SourceSet object.
     */
    public void sourceSet(SourceSet sourceSet) {
        getSourceSet().set(sourceSet);
    }

    /**
     * Specifies the SourceSet to use for this run configuration by its name.
     * @param sourceSetName The name of the source set (e.g., "test").
     */
    public void sourceSet(String sourceSetName) {
        Provider<SourceSet> sourceSetProvider = project.provider(() -> project.getExtensions()
                .getByType(JavaPluginExtension.class)
                .getSourceSets()
                .getByName(sourceSetName));
        getSourceSet().set(sourceSetProvider);
    }

    /**
     * Adds a single argument to the JVM command line.
     * @param arg The argument to add (e.g., "-Xmx4G").
     */
    public void jvmArg(String arg) {
        getJvmArgs().add(arg);
    }
    /**
     * Adds multiple arguments to the JVM command line.
     * @param args The arguments to add.
     */
    public void jvmArgs(String... args) {
        getJvmArgs().addAll(Arrays.asList(args));
    }
    /**
     * Adds multiple arguments from a collection to the JVM command line.
     * @param args The collection of arguments to add.
     */
    public void jvmArgs(Collection<String> args) {
        getJvmArgs().addAll(args);
    }

    /**
     * Sets a JVM system property. e.g., property("my.flag", "true") -> "-Dmy.flag=true"
     */
    public void property(String name, String value) {
        jvmArg("-D" + name + "=" + value);
    }
    /**
     * Sets a JVM system property. e.g., property("my.flag") -> "-Dmy.flag"
     */
    public void property(String name) {
        jvmArg("-D" + name);
    }
    /**
     * Sets multiple JVM system properties from a map.
     */
    public void properties(Map<String, String> props) {
        props.forEach(this::property);
    }

    /**
     * Adds a single argument to the program's argument list.
     * @param arg The argument to add.
     */
    public void programArg(String arg) {
        getProgramArgs().add(arg);
    }
    /**
     * Adds multiple arguments to the program's argument list.
     * @param args The arguments to add.
     */
    public void programArgs(String... args) {
        getProgramArgs().addAll(Arrays.asList(args));
    }
    /**
     * Adds multiple arguments from a collection to the program's argument list.
     * @param args The collection of arguments to add.
     */
    public void programArgs(Collection<String> args) {
        getProgramArgs().addAll(args);
    }

    /**
     * Sets an environment variable for the process.
     * @param name The name of the environment variable.
     * @param value The value of the environment variable.
     */
    public void environmentVariable(String name, Object value) {
        getEnvironmentVariables().put(name, value);
    }
}
