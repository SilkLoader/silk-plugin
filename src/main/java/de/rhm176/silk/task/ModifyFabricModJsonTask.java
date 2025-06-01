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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.*;

/**
 * A Gradle task that modifies a {@code fabric.mod.json} file to include references
 * to sub-mod JARs that are bundled within the main mod JAR.
 * <p>
 * This task reads an existing {@code fabric.mod.json} file, parses its JSON content,
 * and adds new entries to the "jars" array for any specified bundled JARs that are
 * not already listed. If the "jars" array does not exist, it will be created.
 * The modification is done atomically using a temporary file.
 */
public abstract class ModifyFabricModJsonTask extends DefaultTask {

    /**
     * The input {@code fabric.mod.json} file to be read and potentially modified.
     * <p>
     * This property is optional. If the file does not exist when {@link #getBundledJarNames()}
     * is not empty, the task will fail. If {@link #getBundledJarNames()} is empty,
     * the task will skip execution, and this input file is not strictly required.
     *
     * @return A {@link RegularFileProperty} for the input {@code fabric.mod.json} file.
     */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getInputJsonFile();

    /**
     * The output {@code fabric.mod.json} file where the (potentially) modified content
     * will be written.
     * <p>
     * This typically points to the same location as {@link #getInputJsonFile()} if the
     * modification is done in-place, or to a new location if the original is preserved.
     * The task ensures that if modifications are made, they are written to this file.
     * If no modifications are needed (e.g., all bundled JARs are already listed or
     * no bundled JARs are specified), the content of this file will remain unchanged
     * (or identical to the input if it's a different file).
     *
     * @return A {@link RegularFileProperty} for the output {@code fabric.mod.json} file.
     */
    @OutputFile
    public abstract RegularFileProperty getOutputJsonFile();

    /**
     * Provides a list of filenames for the JARs that are bundled within the main mod's JAR
     * (e.g., "submod-a.jar", "submod-b.jar").
     * For each filename in this list, the task will ensure an entry of the form
     * {@code {"file": "META-INF/jars/filename.jar"}} exists in the "jars" array
     * of the {@code fabric.mod.json} file.
     *
     * @return A {@link ListProperty} containing the simple names of the bundled JAR files.
     */
    @Input
    public abstract ListProperty<String> getBundledJarNames();

    /**
     * Executes the task action to modify the {@code fabric.mod.json} file.
     * @throws GradleException if the {@code fabric.mod.json} file is missing when it's required,
     * or if any I/O error occurs during processing.
     */
    @TaskAction
    public void execute() {
        File inputFile = getInputJsonFile().get().getAsFile();
        File outputFile = getOutputJsonFile().get().getAsFile();

        List<String> bundledJarFileNames = getBundledJarNames().getOrElse(List.of());

        if (bundledJarFileNames.isEmpty()) {
            return;
        }

        if (!inputFile.exists()) {
            throw new GradleException("Silk: Cannot modify fabric.mod.json. Input file '" + inputFile.getAbsolutePath()
                    + "' does not exist, but there are subproject JARs to bundle: " + bundledJarFileNames
                    + ". Ensure fabric.mod.json is generated or manually provided in src/main/resources.");
        }

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

        try {
            JsonNode rootNode = objectMapper.readTree(inputFile);
            if (!rootNode.isObject()) {
                getLogger()
                        .error(
                                "Silk: fabric.mod.json content at {} is not a JSON object. Skipping modification.",
                                inputFile.getAbsolutePath());
                return;
            }
            ObjectNode objectNode = (ObjectNode) rootNode;

            ArrayNode jarsArrayNode;
            if (objectNode.has("jars")) {
                JsonNode jarsField = objectNode.get("jars");
                if (jarsField.isArray()) {
                    jarsArrayNode = (ArrayNode) jarsField;
                } else {
                    getLogger()
                            .warn(
                                    "Silk: 'jars' field in fabric.mod.json at {} is present but not an array. It will be overwritten.",
                                    inputFile.getAbsolutePath());
                    jarsArrayNode = objectMapper.createArrayNode();
                    objectNode.set("jars", jarsArrayNode);
                }
            } else {
                jarsArrayNode = objectMapper.createArrayNode();
                objectNode.set("jars", jarsArrayNode);
            }

            Set<String> existingJarPaths = new HashSet<>();
            for (JsonNode entry : jarsArrayNode) {
                if (entry.isObject() && entry.has("file") && entry.get("file").isTextual()) {
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
                File tempFile = Files.createTempFile(outputFile.getParentFile().toPath(), outputFile.getName(), ".tmp")
                        .toFile();
                objectMapper.writeValue(tempFile, objectNode);
                Files.move(
                        tempFile.toPath(),
                        outputFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (IOException e) {
            throw new GradleException(
                    "Silk: Failed to read or write fabric.mod.json at " + inputFile.getAbsolutePath(), e);
        }
    }
}
