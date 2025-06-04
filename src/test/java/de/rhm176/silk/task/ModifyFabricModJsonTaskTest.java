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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import de.rhm176.silk.SilkPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModifyFabricModJsonTaskTest {

    @TempDir
    Path projectDir;

    private File buildFile;
    private File inputFile;
    private File outputFile;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        buildFile = new File(projectDir.toFile(), "build.gradle");
        inputFile = new File(projectDir.toFile(), "fabric.mod.json");
        outputFile = new File(projectDir.toFile(), "fabric.mod.json");

        objectMapper = new ObjectMapper();
    }

    private void writeBuildGradle(String inputFilepath, String outputFilepath, List<String> bundledJars)
            throws IOException {
        String bundledJarsString = bundledJars.stream()
                .map(s -> "\"" + s + "\"")
                .reduce((s1, s2) -> s1 + ", " + s2)
                .orElse("");

        String buildGradleContent = String.format(
                """
            plugins {
                id 'de.rhm176.silk.silk-plugin'
            }

            tasks.%s {
                %s
                outputJsonFile.set(layout.projectDirectory.file("%s"))
                bundledJarNames.set([%s])
            }
            """,
                SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME,
                inputFilepath == null
                        ? ""
                        : "inputJsonFile.set(layout.projectDirectory.file(\"" + inputFilepath + "\"))",
                outputFilepath,
                bundledJarsString);
        Files.writeString(buildFile.toPath(), buildGradleContent, StandardCharsets.UTF_8);
    }

    private void writeInitialJson(String content) throws IOException {
        if (!inputFile.getParentFile().exists()) {
            assertTrue(inputFile.getParentFile().mkdirs(), "Could not create input parent directory.");
        }
        Files.writeString(inputFile.toPath(), content, StandardCharsets.UTF_8);
    }

    private GradleRunner createRunner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath();
    }

    private void assertTaskOutcome(TaskOutcome outcome, BuildTask task) {
        assertNotNull(task);
        assertEquals(outcome, task.getOutcome());
    }

    @Test
    @DisplayName("Task should do nothing and succeed if no bundled JARs are specified")
    void testNoBundledJars_shouldDoNothing() throws IOException {
        writeBuildGradle("fabric.mod.json", "fabric.mod.json", List.of());
        String initialJsonContent = "{\"id\":\"test-mod\"}";
        writeInitialJson(initialJsonContent);

        BuildResult result = createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace")
                .build();

        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(outputFile.exists());
        String outputContent = Files.readString(outputFile.toPath());
        assertEquals(initialJsonContent, outputContent);
    }

    @Test
    @DisplayName("Should add new JARs to a 'jars' array if it's missing in the input JSON")
    void testAddNewJars_whenJarsArrayMissing() throws IOException {
        writeBuildGradle("fabric.mod.json", "fabric.mod.json.out", List.of("submod1.jar", "submod2.jar"));
        outputFile = new File(projectDir.toFile(), "fabric.mod.json.out");

        String initialJsonContent = "{\"id\":\"test-mod\", \"version\":\"1.0\"}";
        writeInitialJson(initialJsonContent);

        BuildResult result = createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace")
                .build();

        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(outputFile.exists());

        JsonNode root = objectMapper.readTree(outputFile);
        assertTrue(root.has("jars"));
        ArrayNode jarsArray = (ArrayNode) root.get("jars");
        assertEquals(2, jarsArray.size());
        assertEquals("META-INF/jars/submod1.jar", jarsArray.get(0).get("file").asText());
        assertEquals("META-INF/jars/submod2.jar", jarsArray.get(1).get("file").asText());
    }

    @Test
    @DisplayName("Should add new JARs to an existing empty 'jars' array")
    void testAddNewJars_whenJarsArrayExistsAndIsEmpty() throws IOException {
        writeBuildGradle("fabric.mod.json", "fabric.mod.json", List.of("submod.jar"));
        String initialJsonContent = "{\"id\":\"test-mod\", \"jars\":[]}";
        writeInitialJson(initialJsonContent);

        createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace")
                .build();

        JsonNode root = objectMapper.readTree(outputFile);
        ArrayNode jarsArray = (ArrayNode) root.get("jars");
        assertEquals(1, jarsArray.size());
        assertEquals("META-INF/jars/submod.jar", jarsArray.get(0).get("file").asText());
    }

    @Test
    @DisplayName("Should add new JARs while preserving existing, different JAR entries")
    void testAddNewJars_andPreserveExistingDifferentJars() throws IOException {
        writeBuildGradle("fabric.mod.json", "fabric.mod.json", List.of("newmod.jar"));
        String initialJsonContent =
                """
            {
              "id": "test-mod",
              "jars": [
                {"file": "META-INF/jars/existing.jar"}
              ]
            }
            """;
        writeInitialJson(initialJsonContent);

        createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace")
                .build();

        JsonNode root = objectMapper.readTree(outputFile);
        ArrayNode jarsArray = (ArrayNode) root.get("jars");
        assertEquals(2, jarsArray.size());
        boolean foundExisting = false;
        boolean foundNew = false;
        for (JsonNode entry : jarsArray) {
            String fileVal = entry.get("file").asText();
            if ("META-INF/jars/existing.jar".equals(fileVal)) foundExisting = true;
            if ("META-INF/jars/newmod.jar".equals(fileVal)) foundNew = true;
        }
        assertTrue(foundExisting, "Existing JAR entry missing");
        assertTrue(foundNew, "New JAR entry missing");
    }

    @Test
    @DisplayName("Should not add duplicate JAR entries if they already exist")
    void testJarsAlreadyExist_shouldNotAddDuplicates() throws IOException {
        writeBuildGradle("fabric.mod.json", "fabric.mod.json", List.of("submod1.jar"));
        String initialJsonContent =
                """
            {
              "id": "test-mod",
              "jars": [
                {"file": "META-INF/jars/submod1.jar"}
              ]
            }
            """;
        writeInitialJson(initialJsonContent);

        createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace")
                .build();

        JsonNode root = objectMapper.readTree(outputFile);
        ArrayNode jarsArray = (ArrayNode) root.get("jars");
        assertEquals(1, jarsArray.size());
        assertEquals("META-INF/jars/submod1.jar", jarsArray.get(0).get("file").asText());
    }

    @Test
    @DisplayName("'jars' field is not an array: should overwrite it and log a warning")
    void testJarsFieldIsNotArray_shouldOverwriteAndWarn() throws IOException {
        writeBuildGradle("fabric.mod.json", "fabric.mod.json", List.of("submod.jar"));
        String initialJsonContent = "{\"id\":\"test-mod\", \"jars\":\"not-an-array\"}";
        writeInitialJson(initialJsonContent);

        BuildResult result = createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace", "--warn")
                .build();

        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(result.getOutput().contains("Silk: 'jars' field in fabric.mod.json")
                && result.getOutput().contains("is present but not an array. It will be overwritten."));

        JsonNode root = objectMapper.readTree(outputFile);
        assertTrue(root.get("jars").isArray());
        ArrayNode jarsArray = (ArrayNode) root.get("jars");
        assertEquals(1, jarsArray.size());
        assertEquals("META-INF/jars/submod.jar", jarsArray.get(0).get("file").asText());
    }

    @Test
    @DisplayName("Input JSON is syntactically invalid: task should fail and log an error")
    void testInputIsInvalidJson_shouldLogErrorAndSkip() throws IOException {
        outputFile = new File(projectDir.toFile(), "fabric.mod.json.out");
        writeBuildGradle("fabric.mod.json", "fabric.mod.json.out", List.of("submod.jar"));
        String initialJsonContent = "{\"id\":\"test-mod\", ";
        writeInitialJson(initialJsonContent);

        BuildResult result = createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace", "--info")
                .buildAndFail();

        assertTaskOutcome(TaskOutcome.FAILED, result.task(":" + SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(result.getOutput().contains("Silk: Failed to read or write fabric.mod.json"));

        if (!inputFile.equals(outputFile)) {
            assertFalse(
                    outputFile.exists(), "Output file should not be created if input JSON is invalid and read fails.");
        }
    }

    @Test
    @DisplayName("Input JSON is not a JSON object: task should fail")
    void testInputJsonIsNotObject_shouldThrowError() throws IOException {
        outputFile = new File(projectDir.toFile(), "fabric.mod.json.out");
        writeBuildGradle(inputFile.getName(), outputFile.getName(), List.of("submod.jar"));
        String initialJsonContent = "[\"not-an-object\"]";
        writeInitialJson(initialJsonContent);

        BuildResult result = createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace", "--warn")
                .buildAndFail();

        assertTaskOutcome(TaskOutcome.FAILED, result.task(":" + SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME));
        assertFalse(
                outputFile.exists(),
                "Output file should not be created or modified if task skips due to non-object JSON.");
    }

    @Test
    @DisplayName("No bundled JARs and different output file: should not create or modify the output file")
    void testNoBundledJars_outputDifferentFromInput_shouldNotCreateOrModifyOutput() throws IOException {
        outputFile = new File(projectDir.toFile(), "fabric.mod.json.out");
        writeBuildGradle(inputFile.getName(), outputFile.getName(), List.of());
        String initialJsonContent = "{\"id\":\"test-mod-for-no-op-output-test\"}";
        writeInitialJson(initialJsonContent);

        BuildResult result = createRunner(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace")
                .build();

        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME));
        assertFalse(
                outputFile.exists(),
                "Output file should not be created if no bundled jars and output is different from input.");
        assertTrue(Files.exists(inputFile.toPath()));
        assertEquals(initialJsonContent, Files.readString(inputFile.toPath()));
    }
}
