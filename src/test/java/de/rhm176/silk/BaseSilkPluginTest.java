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

import de.rhm176.silk.task.ModifyFabricModJsonTask;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

abstract class BaseSilkPluginTest {

    @TempDir
    protected File projectDir;

    protected File buildFile;
    protected File settingsFile;
    protected File mainResourcesDir;
    protected File dummyEquilinoxJar;
    protected File dummyLoaderJar;

    @BeforeEach
    void setupBase() throws IOException {
        buildFile = new File(projectDir, "build.gradle");
        settingsFile = new File(projectDir, "settings.gradle");
        writeFile(settingsFile, "rootProject.name = 'test-silk-project'\n");

        mainResourcesDir = new File(projectDir, "src/main/resources");
        mainResourcesDir.mkdirs();

        // Apply the java plugin as SilkPlugin applies it
        appendBuildGradle("plugins {", "    id 'de.rhm176.silk.silk-plugin'", "}");

        dummyEquilinoxJar = new File(projectDir, "EquilinoxWindows.jar");
        createDummyJar(dummyEquilinoxJar.toPath(), null, "dummy.game.MainGameClass");

        dummyLoaderJar = new File(projectDir, "dummy-silk-loader.jar");
    }

    protected void writeFile(File destination, String content) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(destination))) {
            writer.println(content);
        }
    }

    protected void appendBuildGradle(String... lines) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(buildFile, true))) {
            for (String line : lines) {
                writer.println(line);
            }
            writer.println(); // Add a newline for separation
        }
    }

    protected void createDummyJar(Path jarPath, String mainClassAttribute, String... classEntries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (mainClassAttribute != null && !mainClassAttribute.isEmpty()) {
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClassAttribute);
        }

        Files.deleteIfExists(jarPath);
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            if (classEntries != null) {
                for (String classEntry : classEntries) {
                    jos.putNextEntry(new ZipEntry(classEntry.replace('.', '/') + ".class"));
                    // Simple dummy class content
                    jos.write(("public class " + classEntry.substring(classEntry.lastIndexOf('.') + 1) + " {}")
                            .getBytes());
                    jos.closeEntry();
                }
            }
        }
    }

    protected GradleRunner createRunner(String... arguments) {
        List<String> args = new ArrayList<>(Arrays.asList(arguments));
        args.add("--stacktrace");

        return GradleRunner.create()
                .withProjectDir(projectDir)
                .withPluginClasspath()
                .withArguments(args);
    }

    protected void configurePluginWithGameJar(
            String gameJarFileNameInProjectDir,
            String silkLoaderCoords,
            String silkLoaderMainOverride,
            Boolean generateFmj)
            throws IOException {
        appendBuildGradle(
                "tasks.named('" + SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME + "', "
                        + ModifyFabricModJsonTask.class.getName() + ").configure {task ->",
                "    task.enabled = false",
                "}");

        if (gameJarFileNameInProjectDir != null && !gameJarFileNameInProjectDir.isEmpty()) {
            appendBuildGradle(
                    "// Ensure 'equilinox' configuration is declared if not already by plugin",
                    "// (Plugin already creates it, but explicit here for clarity in test context)",
                    "// configurations { equilinox }",
                    "dependencies {",
                    "    equilinox files('" + gameJarFileNameInProjectDir.replace("\\", "\\\\") + "')",
                    "}");
        }

        StringBuilder silkConfig = new StringBuilder("silk {\n");

        if (silkLoaderCoords != null) {
            File localCoordsFile = new File(projectDir, silkLoaderCoords);
            if (localCoordsFile.exists() && localCoordsFile.isFile()) {
                silkConfig
                        .append("    silkLoaderCoordinates = files('")
                        .append(silkLoaderCoords.replace("\\", "\\\\"))
                        .append("')\n");
            } else if (silkLoaderCoords.contains(":")) {
                silkConfig
                        .append("    silkLoaderCoordinates = '")
                        .append(silkLoaderCoords)
                        .append("'\n");
            } else if (!silkLoaderCoords.trim().isEmpty()) {
                silkConfig
                        .append("    silkLoaderCoordinates = '")
                        .append(silkLoaderCoords)
                        .append("'\n");
            }
        }

        if (silkLoaderMainOverride != null) {
            silkConfig
                    .append("    silkLoaderMainClassOverride = '")
                    .append(silkLoaderMainOverride)
                    .append("'\n");
        }

        if (generateFmj != null) {
            silkConfig
                    .append("    generateFabricModJson = ")
                    .append(generateFmj)
                    .append("\n");
            if (generateFmj) {
                silkConfig.append("    fabric.id = 'mytestmod'\n");
                silkConfig.append("    fabric.version = '1.0.0'\n");
            }
        }
        silkConfig.append("}\n");
        appendBuildGradle(silkConfig.toString());
    }
}
