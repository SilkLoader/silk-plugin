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

import static org.junit.jupiter.api.Assertions.*;

import de.rhm176.silk.SilkPlugin;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExtractNativesTaskTest {

    @TempDir
    Path projectDir;

    private File buildFile;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = projectDir.resolve("build.gradle").toFile();
        String buildGradleContent =
                """
                plugins {
                    id 'de.rhm176.silk.silk-plugin'
                }
                """;
        Files.writeString(buildFile.toPath(), buildGradleContent);
    }

    private void writeTaskConfiguration(String inputJarPath, String nativesDirPath, String osNameOverride)
            throws IOException {
        String currentBuildContent = Files.readString(buildFile.toPath());
        String taskConfig = String.format(
                """
            tasks.named('%s', %s) {
                inputJar.set(project.file('%s'))
                nativesDir.set(project.layout.buildDirectory.dir('%s'))
                %s
            }
            """,
                SilkPlugin.EXTRACT_NATIVES_TASK_NAME,
                ExtractNativesTask.class.getName(),
                inputJarPath.replace("\\", "\\\\"),
                nativesDirPath.replace("\\", "\\\\"),
                osNameOverride != null ? "osName.set('" + osNameOverride + "')" : "");
        Files.writeString(buildFile.toPath(), currentBuildContent + "\n" + taskConfig);
    }

    private File createTestJar(String jarName, Map<String, String> entries) throws IOException {
        File jarFile = projectDir.resolve(jarName).toFile();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jos.putNextEntry(jarEntry);
                jos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return jarFile;
    }

    private GradleRunner createRunner() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(SilkPlugin.EXTRACT_NATIVES_TASK_NAME, "--stacktrace")
                .withPluginClasspath();
    }

    private void assertTaskOutcome(TaskOutcome outcome, BuildTask task) {
        assertNotNull(task);
        assertEquals(outcome, task.getOutcome());
    }

    private int getFileCount(File directory) {
        if (directory == null || !directory.exists() || directory.isFile()) return 0;
        File[] files = directory.listFiles(f -> !f.isDirectory());

        return files != null ? files.length : 0;
    }

    @ParameterizedTest(name = "[{index}] OS: {0}, File: {1}, ShouldExtract: {2}")
    @CsvSource({
        "Windows, native.dll, true",
        "Windows, script.sh, false",
        "Windows, library.so, false",
        "Windows, image.png, false",
        "Windows, subfolder/native.dll, false",
        "Linux, library.so, true",
        "Linux, native.dll, false",
        "Linux, lib/library.so, false",
        "Mac OS X, libgraphics.jnilib, true",
        "Mac OS X, libsound.dylib, true",
        "Mac OS X, libcompute.so, false",
        "Darwin, libstuff.dylib, true"
    })
    @DisplayName("Verify native extraction logic based on OS and file extension")
    void testNativeExtractionPerOs(String osName, String fileNameInJar, boolean shouldExtract) throws IOException {
        Map<String, String> jarEntries = new HashMap<>();
        jarEntries.put(fileNameInJar, "dummy native content");
        jarEntries.put("another.txt", "some text data");

        File inputJar = createTestJar("input_natives.jar", jarEntries);
        String nativesOutputDirName = "extracted_natives_" + osName.replaceAll("[^a-zA-Z0-9]", "");
        writeTaskConfiguration(inputJar.getName(), nativesOutputDirName, osName);

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.EXTRACT_NATIVES_TASK_NAME));

        File outputDir =
                projectDir.resolve("build").resolve(nativesOutputDirName).toFile();
        assertTrue(outputDir.exists() && outputDir.isDirectory());

        File extractedFile = new File(
                outputDir,
                fileNameInJar.contains("/")
                        ? fileNameInJar.substring(fileNameInJar.lastIndexOf("/") + 1)
                        : fileNameInJar);
        assertEquals(
                shouldExtract,
                extractedFile.exists(),
                "File " + fileNameInJar + " extraction status was not as expected for OS " + osName);

        File otherFile = new File(outputDir, "another.txt");
        assertFalse(otherFile.exists(), "Non-native file 'another.txt' should not be extracted.");

        if (shouldExtract) {
            assertEquals(
                    1,
                    getFileCount(outputDir),
                    "Only one file should be extracted when an extractable native is present.");
            assertEquals("dummy native content", Files.readString(extractedFile.toPath()));
        } else if (outputDir.listFiles() != null) {
            assertEquals(
                    0,
                    getFileCount(outputDir),
                    "No files should be extracted when the specific native is not for the OS or is in a subfolder.");
        }
    }

    @Test
    @DisplayName("Output directory should be cleared of pre-existing files before extraction")
    void testOutputDirectoryIsCleared() throws IOException {
        File inputJar = createTestJar("input.jar", Map.of("test.dll", "dll_content"));
        String nativesOutputDirName = "cleared_natives";
        File outputDir =
                projectDir.resolve("build").resolve(nativesOutputDirName).toFile();

        assertTrue(outputDir.mkdirs(), "Could not create output directory");
        File preexistingFile = new File(outputDir, "stale.txt");
        Files.writeString(preexistingFile.toPath(), "stale data");
        assertTrue(preexistingFile.exists());

        writeTaskConfiguration(inputJar.getName(), nativesOutputDirName, "Windows");
        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.EXTRACT_NATIVES_TASK_NAME));

        assertFalse(preexistingFile.exists(), "Pre-existing file should have been deleted.");
        File extractedDll = new File(outputDir, "test.dll");
        assertTrue(extractedDll.exists(), "Native DLL should be extracted.");
    }

    @Test
    @DisplayName("Should extract multiple matching native files from JAR root")
    void testExtractsMultipleMatchingNatives() throws IOException {
        Map<String, String> jarEntries = Map.of(
                "first.dll", "content1",
                "second.dll", "content2",
                "not_native.txt", "text",
                "sub/third.dll", "content3");
        File inputJar = createTestJar("multi_natives.jar", jarEntries);
        String nativesOutputDirName = "multi_extracted";
        writeTaskConfiguration(inputJar.getName(), nativesOutputDirName, "Windows");

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.EXTRACT_NATIVES_TASK_NAME));

        File outputDir =
                projectDir.resolve("build").resolve(nativesOutputDirName).toFile();
        assertTrue(new File(outputDir, "first.dll").exists());
        assertTrue(new File(outputDir, "second.dll").exists());
        assertFalse(new File(outputDir, "not_native.txt").exists());
        assertFalse(new File(outputDir, "third.dll").exists());
        assertEquals(2, getFileCount(outputDir));
    }

    @Test
    @DisplayName("Should result in an empty output directory if no matching natives are in the JAR")
    void testNoMatchingNativesInJar() throws IOException {
        File inputJar = createTestJar("no_natives.jar", Map.of("readme.txt", "text", "image.png", "img"));
        String nativesOutputDirName = "no_natives_output";
        writeTaskConfiguration(inputJar.getName(), nativesOutputDirName, "Linux");

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.EXTRACT_NATIVES_TASK_NAME));

        File outputDir =
                projectDir.resolve("build").resolve(nativesOutputDirName).toFile();
        assertTrue(outputDir.exists() && outputDir.isDirectory());
        assertEquals(0, getFileCount(outputDir), "Output directory should be empty.");
    }

    @Test
    @DisplayName("Should not extract any natives for an unrecognized OS")
    void testUnknownOsNoExtraction() throws IOException {
        File inputJar =
                createTestJar("unknown_os.jar", Map.of("native.dll", "d", "native.so", "s", "native.jnilib", "j"));
        String nativesOutputDirName = "unknown_os_output";
        writeTaskConfiguration(inputJar.getName(), nativesOutputDirName, "AmigaOS");

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.EXTRACT_NATIVES_TASK_NAME));

        File outputDir =
                projectDir.resolve("build").resolve(nativesOutputDirName).toFile();
        assertTrue(outputDir.exists() && outputDir.isDirectory());
        assertEquals(0, getFileCount(outputDir), "No natives should be extracted for an unknown OS.");
    }
}
