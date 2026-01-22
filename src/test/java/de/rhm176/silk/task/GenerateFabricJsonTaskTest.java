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
import de.rhm176.silk.SilkPlugin;
import de.rhm176.silk.extension.EntrypointContainerExtension;
import de.rhm176.silk.extension.FabricExtension;
import de.rhm176.silk.extension.PersonExtension;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GenerateFabricJsonTaskTest {

    @TempDir
    Path projectDir;

    private File buildFile;
    private File outputFile;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = projectDir.resolve("build.gradle").toFile();
        outputFile = projectDir.resolve("build/fabric.mod.json").toFile();

        String buildGradleContent = String.format(
                """
            plugins {
                id 'de.rhm176.silk.silk-plugin'
            }

            silk {
              generateFabricModJson = true
            }

            tasks.named('%s', %s) {
                outputFile.set(layout.buildDirectory.file("fabric.mod.json"))
                mainResourceDirectories.from(layout.projectDirectory.dir("src/main/resources"))
                id.set(project.name)
                version.set(project.version.toString())
                shouldVerify.set(true)
            }
            """,
                SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME, GenerateFabricJsonTask.class.getName());
        Files.writeString(buildFile.toPath(), buildGradleContent);

        Files.createDirectories(projectDir.resolve("src/main/resources"));
        objectMapper = new ObjectMapper();
    }

    private void appendTaskConfig(String config) throws IOException {
        String currentBuildContent = Files.readString(buildFile.toPath());
        Files.writeString(
                buildFile.toPath(),
                currentBuildContent + "\n"
                        + String.format(
                                "tasks.named('%s', %s) {\n",
                                SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME, GenerateFabricJsonTask.class.getName())
                        + config + "}");
    }

    private GradleRunner createRunner() {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME, "--stacktrace")
                .withPluginClasspath();
    }

    private void createResourceFile(String path, String content) throws IOException {
        File resourceFile =
                projectDir.resolve("src/main/resources").resolve(path).toFile();
        Files.createDirectories(resourceFile.getParentFile().toPath());
        Files.writeString(resourceFile.toPath(), content, StandardCharsets.UTF_8);
    }

    private void assertTaskOutcome(TaskOutcome outcome, BuildTask task) {
        assertNotNull(task);
        assertEquals(outcome, task.getOutcome());
    }

    @Test
    @DisplayName("Minimal valid config (ID and Version) should generate successfully")
    void minimalValidConfig_generatesSuccessfully() throws IOException {
        appendTaskConfig("""
                id.set("my-mod")
                version.set("1.0.0")
            """);

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(outputFile.exists());

        JsonNode root = objectMapper.readTree(outputFile);
        assertEquals(FabricExtension.SCHEMA_VERSION, root.get("schemaVersion").asInt());
        assertEquals("my-mod", root.get("id").asText());
        assertEquals("1.0.0", root.get("version").asText());
        assertEquals("*", root.get("environment").asText());
    }

    @Test
    @DisplayName("Comprehensive valid config generates all specified fields correctly")
    void comprehensiveValidConfig_generatesAllFields() throws IOException {
        createResourceFile("mymixin.mixins.json", "{}");
        createResourceFile("myaw.accesswidener", "classTweaker v1 official\naccessible class com.example.Test");
        createResourceFile("assets/mymod/icon.png", "dummy png content");

        appendTaskConfig(String.format(
                """
                id.set("comprehensive-mod")
                version.set("0.1.0-SNAPSHOT")
                shouldVerify.set(true)
                modName.set("Comprehensive Mod Name")
                modDescription.set("A detailed description.")
                authors.add(project.objects.newInstance(%s.class).tap { person ->
                    person.name.set("Author One")
                    person.contact.put("homepage", "https://author.one")
                })
                contributors.add(project.objects.newInstance(%s.class).tap { person ->
                    person.name.set("Contributor Alpha")
                })
                licenses.set(["MIT", "Apache-2.0"])
                contact.put("homepage", "https://comprehensive.mod")
                contact.put("issues", "https://comprehensive.mod/issues")
                contact.put("email", "contact@comprehensive.mod")
                jars.add("META-INF/jars/lib1.jar")
                languageAdapters.put("fabric-kotlin", "net.fabricmc.language.kotlin.KotlinAdapter")
                mixins.add("mymixin.mixins.json")
                accessWidener.set("myaw.accesswidener")
                iconFile.set("assets/mymod/icon.png")
                depends.put("fabricloader", ">=0.15.0")
                recommends.put("another-mod", "*")
                customData.put("my-custom:setting", "custom_value")
                customData.put("my-custom:nested", [keyA: 123, keyB: true])

                entrypointsContainer.set(project.objects.newInstance(%s.class).tap { container ->
                    container.type("main") { listBuilder ->
                        listBuilder.entry("com.example.MainClass")
                    }
                    container.type("client") { listBuilder ->
                        listBuilder.entry { entrypoint ->
                            entrypoint.value.set("com.example.ClientClass")
                            entrypoint.adapter.set("fabric-kotlin")
                        }
                    }
                })
            """,
                PersonExtension.class.getName(),
                PersonExtension.class.getName(),
                EntrypointContainerExtension.class.getName()));

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));

        JsonNode root = objectMapper.readTree(outputFile);
        assertEquals("comprehensive-mod", root.get("id").asText());
        assertEquals("Comprehensive Mod Name", root.get("name").asText());
        assertEquals("A detailed description.", root.get("description").asText());

        assertEquals(2, root.get("license").size());
        assertEquals("MIT", root.get("license").get(0).asText());

        assertEquals(
                "https://comprehensive.mod", root.get("contact").get("homepage").asText());
        assertEquals(
                "contact@comprehensive.mod", root.get("contact").get("email").asText());

        assertEquals(1, root.get("authors").size());
        assertEquals("Author One", root.get("authors").get(0).get("name").asText());
        assertEquals(
                "https://author.one",
                root.get("authors").get(0).get("contact").get("homepage").asText());

        assertEquals(
                "META-INF/jars/lib1.jar", root.get("jars").get(0).get("file").asText());
        assertEquals(
                "net.fabricmc.language.kotlin.KotlinAdapter",
                root.get("languageAdapters").get("fabric-kotlin").asText());
        assertEquals("mymixin.mixins.json", root.get("mixins").get(0).asText());
        assertEquals("myaw.accesswidener", root.get("accessWidener").asText());
        assertEquals("assets/mymod/icon.png", root.get("icon").asText());
        assertEquals(">=0.15.0", root.get("depends").get("fabricloader").asText());
        assertEquals("custom_value", root.get("custom").get("my-custom:setting").asText());
        assertEquals(123, root.get("custom").get("my-custom:nested").get("keyA").asInt());

        assertTrue(root.has("entrypoints"));
        assertEquals(
                "com.example.MainClass",
                root.get("entrypoints").get("main").get(0).asText());
        assertEquals(
                "fabric-kotlin",
                root.get("entrypoints").get("client").get(0).get("adapter").asText());
        assertEquals(
                "com.example.ClientClass",
                root.get("entrypoints").get("client").get(0).get("value").asText());
    }

    @Test
    @DisplayName("shouldVerify=false allows generation with normally invalid data that is still structurally sound")
    void shouldVerifyFalse_allowsNormallyInvalidData() throws IOException {
        appendTaskConfig(
                """
                id.set("mod-with-bad-id-but-no-verify")
                version.set("1.0")
                shouldVerify.set(false)
                mixins.add("nonexistent.mixins.json")
            """);

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));

        JsonNode root = objectMapper.readTree(outputFile);
        assertEquals("mod-with-bad-id-but-no-verify", root.get("id").asText());
        assertTrue(root.has("mixins"));
        assertEquals("nonexistent.mixins.json", root.get("mixins").get(0).asText());
    }

    @Test
    @DisplayName("Missing ID should fail validation")
    void missingId_failsValidation() throws IOException {
        appendTaskConfig("""
                id.set("")
                version.set("1.0.0")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("'id' is mandatory and cannot be empty."));
    }

    @Test
    @DisplayName("Invalid ID format should fail validation")
    void invalidIdFormat_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("MyModIDWithCaps")
                version.set("1.0.0")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Invalid 'id': \"MyModIDWithCaps\""));
    }

    @Test
    @DisplayName("Missing version should fail validation")
    void missingVersion_failsValidation() throws IOException {
        appendTaskConfig("""
                id.set("my-mod")
                version.set("")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("'version' is mandatory and cannot be empty."));
    }

    @Test
    @DisplayName("Author with no name should fail validation")
    void authorNoName_failsValidation() throws IOException {
        appendTaskConfig(String.format(
                """
                id.set("my-mod")
                version.set("1.0.0")
                authors.add(project.objects.newInstance(%s.class).tap { it.name.set("") })
            """,
                PersonExtension.class.getName()));

        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Person 'name' in 'authors' list is mandatory and cannot be empty."));
    }

    @Test
    @DisplayName("Invalid contact email format should fail validation")
    void invalidContactEmail_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                contact.put("email", "not-an-email")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(
                result.getOutput().contains("Invalid email format for 'email' in contact (top-level): not-an-email"));
    }

    @Test
    @DisplayName("Invalid contact HTTP URL format should fail validation")
    void invalidContactHttpUrl_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                contact.put("homepage", "htp://invalid-schema.com")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(
                result.getOutput()
                        .contains(
                                "Contact 'homepage' in contact (top-level) must be a valid HTTP/HTTPS URL: htp://invalid-schema.com"));
    }

    @Test
    @DisplayName("Mixin file not found in resources should fail validation")
    void mixinFileNotFound_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                mixins.add("nonexistent.mixins.json")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(
                result.getOutput()
                        .contains(
                                "Field 'Entry #1 ('nonexistent.mixins.json') in 'mixins' list' references non-existent file: 'nonexistent.mixins.json'"));
    }

    @Test
    @DisplayName("Icon file not PNG should fail validation")
    void iconFileNotPng_failsValidation() throws IOException {
        createResourceFile("icon.txt", "not a png");
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                iconFile.set("icon.txt")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Icon file path 'icon.txt' must be a .png file."));
    }

    @Test
    @DisplayName("Both iconFile and iconSet defined should fail validation")
    void bothIconFileAndSet_failsValidation() throws IOException {
        createResourceFile("icon.png", "dummy-png");
        createResourceFile("icon16.png", "dummy-png-16");

        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                iconFile.set("icon.png")
                iconSet.put("16", "icon16.png")
            """);

        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Both 'iconFile' and 'iconSet' are defined. Please use only one."));
    }

    @Test
    @DisplayName("Invalid entrypoint value format should fail validation")
    void invalidEntrypointValueFormat_failsValidation() throws IOException {
        appendTaskConfig(String.format(
                """
                id.set("my-mod")
                version.set("1.0.0")
                entrypointsContainer.set(project.objects.newInstance(%s.class).tap { container ->
                    container.type("main") { listBuilder ->
                        listBuilder.entry("com.example..InvalidClass")
                    }
                })
            """,
                EntrypointContainerExtension.class.getName()));

        BuildResult result = createRunner().buildAndFail();
        assertTrue(
                result.getOutput()
                        .contains(
                                "Invalid entrypoint 'value' format for type 'main', declaration #1: \"com.example..InvalidClass\""));
    }

    @Test
    @DisplayName("Single license should be string, multiple should be array")
    void licenseFieldFormat_correctForSingleAndMultiple() throws IOException {
        appendTaskConfig(
                """
                id.set("mod-single-license")
                version.set("1.0")
                licenses.set(["CC0-1.0"])
            """);
        BuildResult resultSingle = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, resultSingle.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        JsonNode rootSingle = objectMapper.readTree(outputFile);
        assertEquals("CC0-1.0", rootSingle.get("license").asText());

        Files.deleteIfExists(outputFile.toPath());
        setUp();

        appendTaskConfig(
                """
                id.set("mod-multi-license")
                version.set("1.0")
                licenses.set(["MIT", "Apache-2.0"])
            """);

        BuildResult resultMulti = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, resultMulti.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        JsonNode rootMulti = objectMapper.readTree(outputFile);
        assertTrue(rootMulti.get("license").isArray());
        assertEquals(2, rootMulti.get("license").size());
        assertEquals("MIT", rootMulti.get("license").get(0).asText());
    }

    @Test
    @DisplayName("Entrypoint with default adapter should be string value, custom adapter should be object")
    void entrypointFormat_correctForAdapterUsage() throws IOException {
        appendTaskConfig(String.format(
                """
                id.set("mod-entrypoints")
                version.set("1.0")
                entrypointsContainer.set(project.objects.newInstance(%s.class).tap { container ->
                    container.type("main") { it.entry("com.example.MainDefault") }
                    container.type("client") {
                        it.entry { ep ->
                            ep.value.set("com.example.ClientCustom")
                            ep.adapter.set("myadapter")
                        }
                    }
                })
            """,
                EntrypointContainerExtension.class.getName()));

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));

        JsonNode entrypoints = objectMapper.readTree(outputFile).get("entrypoints");
        assertNotNull(entrypoints);
        assertEquals("com.example.MainDefault", entrypoints.get("main").get(0).asText());

        JsonNode clientEntry = entrypoints.get("client").get(0);
        assertTrue(clientEntry.isObject());
        assertEquals("myadapter", clientEntry.get("adapter").asText());
        assertEquals("com.example.ClientCustom", clientEntry.get("value").asText());
    }

    @Test
    @DisplayName("Person without contact info should be string, with contact should be object")
    void personFormat_correctForContactUsage() throws IOException {
        appendTaskConfig(String.format(
                """
                id.set("mod-persons")
                version.set("1.0")
                authors.add(project.objects.newInstance(%s.class).tap { it.name.set("Simple Author") })
                authors.add(project.objects.newInstance(%s.class).tap {
                    it.name.set("Complex Author")
                    it.contact.put("homepage", "https://complex.author")
                })
            """,
                PersonExtension.class.getName(), PersonExtension.class.getName()));

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));

        JsonNode authors = objectMapper.readTree(outputFile).get("authors");
        assertNotNull(authors);
        assertEquals("Simple Author", authors.get(0).asText());

        JsonNode complexAuthor = authors.get(1);
        assertTrue(complexAuthor.isObject());
        assertEquals("Complex Author", complexAuthor.get("name").asText());
        assertEquals(
                "https://complex.author",
                complexAuthor.get("contact").get("homepage").asText());
    }

    @Test
    @DisplayName("IconSet should be used over iconFile if both are provided (after validation failure if verify=true)")
    void iconSetTakesPrecedenceOverIconFileInOutput() throws IOException {
        createResourceFile("icon.png", "single icon");
        createResourceFile("icon16.png", "set icon 16");

        appendTaskConfig(
                """
                id.set("icon-test-mod")
                version.set("1.0.0")
                shouldVerify.set(false)
                iconFile.set("icon.png")
                iconSet.put("16", "icon16.png")
            """);

        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));

        JsonNode root = objectMapper.readTree(outputFile);
        assertTrue(root.has("icon") && root.get("icon").isObject(), "Icon field should be an object (from iconSet)");
        assertEquals("icon16.png", root.get("icon").get("16").asText());
    }

    @Test
    @DisplayName("Output directory should be created if it doesn't exist")
    void outputDirectoryCreation() throws IOException {
        File customOutputDirParent = projectDir.resolve("custom_output_dir").toFile();
        File customOutputFile = new File(customOutputDirParent, "fabric.mod.json");

        appendTaskConfig(String.format(
                """
                id.set("dir-test-mod")
                version.set("1.0.0")
                outputFile.set(project.file("%s"))
            """,
                customOutputFile.getAbsolutePath().replace("\\", "\\\\")));

        assertFalse(customOutputDirParent.exists());
        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(customOutputFile.exists());
        assertTrue(customOutputDirParent.exists() && customOutputDirParent.isDirectory());
    }

    @Test
    @DisplayName("Resource path traversal with mixin should fail validation")
    void resourcePathTraversal_mixin_failsValidation() throws IOException {
        Path evilDir = projectDir.resolve("evil");
        Files.createDirectories(evilDir);
        Files.writeString(evilDir.resolve("secret.mixins.json"), "{}", StandardCharsets.UTF_8);

        appendTaskConfig("mixins.add(\"../evil/secret.mixins.json\")");

        BuildResult result = createRunner().buildAndFail();
        assertTrue(
                result.getOutput().contains("references non-existent file")
                        || result.getOutput().contains("appears to be outside configured resource directories"),
                "Error message should indicate path traversal or file not found in configured resources. Output: "
                        + result.getOutput());
    }

    @Test
    @DisplayName("IconSet with non-numeric key should fail validation")
    void iconSet_invalidKey_failsValidation() throws IOException {
        createResourceFile("assets/mymod/icon.png", "dummy-png");
        appendTaskConfig("iconSet.put(\"not-a-number\", \"assets/mymod/icon.png\")");

        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Icon set key (size) 'not-a-number' must be a number string."));
    }

    @Test
    @DisplayName("Empty modName should fail validation if present")
    void emptyModName_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                modName.set(" ") // Empty after trim
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("'name' if present, cannot be empty."));
    }

    @Test
    @DisplayName("Empty modDescription should fail validation if present")
    void emptyModDescription_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                modDescription.set(" ") // Empty after trim
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("'description' if present, cannot be empty."));
    }

    @Test
    @DisplayName("Access Widener path as directory should fail validation")
    void accessWidenerPathIsDirectory_failsValidation() throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/resources/myawdir.accesswidener"));
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                accessWidener.set("myawdir.accesswidener")
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("path 'myawdir.accesswidener' (found at")
                && result.getOutput().contains(") is not a file."));
    }

    @Test
    @DisplayName("Mixin path as directory should fail validation")
    void mixinPathIsDirectory_failsValidation() throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/resources/mymixindir.mixins.json"));
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                mixins.add("mymixindir.mixins.json")
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("path 'mymixindir.mixins.json' (found at")
                && result.getOutput().contains(") is not a file."));
    }

    @Test
    @DisplayName("Entrypoint with empty adapter string should fail validation")
    void entrypointEmptyAdapter_failsValidation() throws IOException {
        appendTaskConfig(String.format(
                """
                id.set("my-mod")
                version.set("1.0.0")
                entrypointsContainer.set(project.objects.newInstance(%s.class).tap { container ->
                    container.type("main") { listBuilder ->
                        listBuilder.entry { entrypoint ->
                            entrypoint.value.set("com.example.MyClass")
                            entrypoint.adapter.set(" ") // Empty after trim
                        }
                    }
                })
            """,
                EntrypointContainerExtension.class.getName()));
        BuildResult result = createRunner().buildAndFail();
        assertTrue(
                result.getOutput()
                        .contains(
                                "Entrypoint 'adapter' for type 'main', declaration #1 ('com.example.MyClass') cannot be an empty string if specified"));
    }

    @Test
    @DisplayName("Entrypoint with empty list for declared type should fail validation")
    void entrypointEmptyListForDeclaredType_failsValidation() throws IOException {
        appendTaskConfig(String.format(
                """
                id.set("my-mod")
                version.set("1.0.0")
                entrypointsContainer.set(project.objects.newInstance(%s.class).tap { container ->
                    container.type("main") { /* empty list builder intentionally */ }
                })
            """,
                EntrypointContainerExtension.class.getName()));
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput()
                .contains("Entrypoint list for type 'main' cannot be empty if the type is declared."));
    }

    @Test
    @DisplayName("Dependency map with empty key should fail validation")
    void dependencyMapEmptyKey_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                depends.put(" ", "1.0.0") // Empty after trim
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Mod ID (key) in 'depends' map cannot be null or empty."));
    }

    @Test
    @DisplayName("Dependency map with empty value should fail validation")
    void dependencyMapEmptyValue_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                depends.put("another-mod", " ") // Empty after trim
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput()
                .contains("Version range for mod ID 'another-mod' in 'depends' map cannot be null or empty."));
    }

    @Test
    @DisplayName("LanguageAdapters map with empty key should fail validation")
    void languageAdaptersEmptyKey_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                languageAdapters.put(" ", "com.example.Adapter") // Empty after trim
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Key in 'languageAdapters' map cannot be null or empty."));
    }

    @Test
    @DisplayName("LanguageAdapters map with empty value should fail validation")
    void languageAdaptersEmptyValue_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                languageAdapters.put("my-adapter", " ") // Empty after trim
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput()
                .contains("Value for key 'my-adapter' in 'languageAdapters' map cannot be null or empty."));
    }

    @Test
    @DisplayName("Jars list with empty string entry should fail validation")
    void jarsListWithEmptyString_failsValidation() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                jars.add(" ") // Empty after trim
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Entry 1 in 'jars[*].file' list cannot be null or empty."));
    }

    @Test
    @DisplayName("Person contact with invalid URL should fail validation")
    void personContactInvalidUrl_failsValidation() throws IOException {
        appendTaskConfig(String.format(
                """
                id.set("my-mod")
                version.set("1.0.0")
                authors.add(project.objects.newInstance(%s.class).tap { person ->
                    person.name.set("Test Person")
                    person.contact.put("homepage", "htp://invalid-url")
                })
            """,
                PersonExtension.class.getName()));
        BuildResult result = createRunner().buildAndFail();
        assertTrue(
                result.getOutput()
                        .contains(
                                "Contact 'homepage' in authors entry 'Test Person' contact must be a valid HTTP/HTTPS URL: htp://invalid-url"));
    }

    @Test
    @DisplayName("Mixin with non-JSON extension should fail validation")
    void mixinNonJsonExtension_failsValidation() throws IOException {
        createResourceFile("mymixin.txt", "{}");
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                mixins.add("mymixin.txt")
            """);
        BuildResult result = createRunner().buildAndFail();
        assertTrue(result.getOutput().contains("Mixin entry 'mymixin.txt' should be a JSON file path."));
    }

    @Test
    @DisplayName("Empty authors list should result in no 'authors' key in JSON")
    void emptyAuthorsList_omitsJsonKey() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                // authors is not set, or explicitly authors.set(java.util.Collections.emptyList())
            """);
        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(outputFile.exists());
        JsonNode root = objectMapper.readTree(outputFile);
        assertFalse(root.has("authors"));
    }

    @Test
    @DisplayName("Empty depends map should result in no 'depends' key in JSON")
    void emptyDependsMap_omitsJsonKey() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                // depends is not set, or explicitly depends.set(java.util.Collections.emptyMap())
            """);
        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(outputFile.exists());
        JsonNode root = objectMapper.readTree(outputFile);
        assertFalse(root.has("depends"));
    }

    @Test
    @DisplayName("Empty customData map should result in no 'custom' key in JSON")
    void emptyCustomDataMap_omitsJsonKey() throws IOException {
        appendTaskConfig(
                """
                id.set("my-mod")
                version.set("1.0.0")
                // customData is not set or empty
            """);
        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(outputFile.exists());
        JsonNode root = objectMapper.readTree(outputFile);
        assertFalse(root.has("custom"));
    }

    @Test
    @DisplayName("Empty entrypoints container should result in no 'entrypoints' key in JSON")
    void emptyEntrypoints_omitsJsonKey() throws IOException {
        appendTaskConfig(String.format(
                """
            id.set("my-mod")
            version.set("1.0.0")
            entrypointsContainer.set(project.objects.newInstance(%s.class)) // Empty container
            """,
                EntrypointContainerExtension.class.getName()));
        BuildResult result = createRunner().build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(outputFile.exists());
        JsonNode root = objectMapper.readTree(outputFile);
        assertFalse(root.has("entrypoints"));
    }
}
