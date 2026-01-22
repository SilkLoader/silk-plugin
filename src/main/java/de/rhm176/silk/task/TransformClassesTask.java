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
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.objectweb.asm.*;

/**
 * A Gradle task that transforms class files from an input JAR by adding specified interfaces
 * to certain classes. The interface mappings are primarily sourced from {@code fabric.mod.json}
 * files found in the {@link #getModConfigurationSources()}.
 * <p>
 * The output of this task is a new JAR containing all original entries, with the specified
 * classes modified to implement the additional interfaces.
 */
public abstract class TransformClassesTask extends DefaultTask {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private ClassTweaker classTweaker;

    /**
     * The input JAR file whose classes are to be transformed.
     *
     * @return A {@link RegularFileProperty} for the input JAR.
     */
    @InputFile
    public abstract RegularFileProperty getInputJar();

    /**
     * A collection of files that serve as sources for interface mapping configurations.
     * <p>
     * The task will look for a {@code fabric.mod.json} entry within any JARs provided in this collection.
     * Mappings are expected under the JSON path {@code custom."silk:injected_interfaces"}.
     *
     * @return A {@link ConfigurableFileCollection} for mod configuration sources.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getModConfigurationSources();

    /**
     * The output JAR file where the transformed classes and all other original entries will be stored.
     *
     * @return A {@link RegularFileProperty} for the output transformed JAR.
     */
    @OutputFile
    public abstract RegularFileProperty getOutputTransformedJar();

    /**
     * Executes the transformation process.
     * It reads the input JAR, parses interface injection configurations from the
     * {@link #getModConfigurationSources()}, applies these transformations to the relevant
     * class files using ASM, and writes the resulting (potentially modified) classes
     * and all other original JAR entries to the output JAR.
     *
     * @throws GradleException if the input JAR does not exist, if the output directory cannot be created,
     * or if any error occurs during JAR processing or bytecode transformation.
     */
    @TaskAction
    public void transform() {
        File inputJarFile = getInputJar().get().getAsFile();
        File outputJarFile = getOutputTransformedJar().get().getAsFile();
        classTweaker = ClassTweaker.newInstance();
        classTweaker.visitHeader("official");

        if (!inputJarFile.exists()) {
            throw new GradleException("Input JAR does not exist: " + inputJarFile.getAbsolutePath());
        }

        File outputDir = outputJarFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                throw new GradleException("Could not create output directory: " + outputDir.getAbsolutePath());
            }
        }
        for (File sourceFileOrJar : getModConfigurationSources().getFiles().stream()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .toList()) {
            if (!sourceFileOrJar.exists()) continue;

            if (sourceFileOrJar.isFile() && sourceFileOrJar.getName().equals("fabric.mod.json")) {
                try (InputStream is = new FileInputStream(sourceFileOrJar)) {
                    processFabricModJsonStream(is, sourceFileOrJar.getAbsolutePath(), null);
                } catch (IOException e) {
                    throw new GradleException(
                            "Silk: Failed to read direct fabric.mod.json: " + sourceFileOrJar.getAbsolutePath(), e);
                }
            } else if (sourceFileOrJar.isFile()
                    && sourceFileOrJar.getName().toLowerCase().endsWith(".jar")) {
                try (JarFile jar = new JarFile(sourceFileOrJar)) {
                    JarEntry fmjEntry = jar.getJarEntry("fabric.mod.json");
                    if (fmjEntry != null) {
                        try (InputStream is = jar.getInputStream(fmjEntry)) {
                            processFabricModJsonStream(is, sourceFileOrJar.getName() + "!fabric.mod.json", jar);
                        }
                    }
                } catch (IOException e) {
                    getLogger().warn("Silk: Could not read JAR: {}", sourceFileOrJar.getAbsolutePath(), e);
                }
            }
        }

        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(new FileInputStream(inputJarFile)));
                JarOutputStream jos =
                        new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputJarFile)))) {

            JarEntry originalEntry;
            while ((originalEntry = jis.getNextJarEntry()) != null) {
                ZipEntry entryToWrite = new ZipEntry(originalEntry.getName());
                if (originalEntry.getTime() != -1) {
                    entryToWrite.setTime(originalEntry.getTime());
                }

                jos.putNextEntry(entryToWrite);

                if (originalEntry.isDirectory()) {
                    jos.closeEntry();
                    continue;
                }

                byte[] entryBytes = readEntryData(jis);
                if (originalEntry.getName().endsWith(".class")) {
                    String classNameInternal = originalEntry
                            .getName()
                            .substring(0, originalEntry.getName().length() - ".class".length());

                    getLogger().debug("Silk: Transforming class {}: applying CT rules.", classNameInternal);
                    entryBytes = transformClassBytecode(entryBytes);
                }
                jos.write(entryBytes);
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new GradleException("Error during JAR transformation process", e);
        }
    }

    /**
     * Processes a single fabric.mod.json stream to extract interface mappings and AW file info.
     *
     * @param fmjStream InputStream of the fabric.mod.json content.
     * @param fmjSourceDescription Description of where this fmjStream comes from (for logging).
     * @param containingJar Optional JarFile if the fmjStream comes from within a JAR, used to resolve AW paths.
     */
    private void processFabricModJsonStream(InputStream fmjStream, String fmjSourceDescription, JarFile containingJar)
            throws IOException {
        JsonNode awPathNode = OBJECT_MAPPER.readTree(fmjStream).path("accessWidener");
        if (awPathNode.isTextual()) {
            String awPath = awPathNode.asText();
            String ref = "";
            if (awPath != null && !awPath.trim().isEmpty()) {
                try {
                    byte[] data = null;
                    if (containingJar != null) {
                        ref = containingJar.getName();

                        JarEntry awEntry = containingJar.getJarEntry(awPath);
                        if (awEntry != null) {
                            try (InputStream awStream = containingJar.getInputStream(awEntry)) {
                                data = awStream.readAllBytes();
                            }
                        } else {
                            getLogger()
                                    .warn(
                                            "Silk: AccessWidener file '{}' not found inside JAR '{}' (referenced by {}).",
                                            awPath,
                                            ref,
                                            fmjSourceDescription);
                        }
                    } else {
                        File fmjFile = new File(fmjSourceDescription);
                        File awFile = new File(fmjFile.getParentFile(), awPath);
                        ref = awFile.getAbsolutePath();

                        if (awFile.exists() && awFile.isFile()) {
                            data = Files.readAllBytes(awFile.toPath());
                        } else {
                            getLogger()
                                    .warn(
                                            "AccessWidener file '{}' (resolved to '{}') not found on filesystem (referenced by {}).",
                                            awPath,
                                            ref,
                                            fmjSourceDescription);
                        }
                    }

                    if (data != null) {
                        final ClassTweakerReader.Header header = ClassTweakerReader.readHeader(data);
                        if (!header.getNamespace().equals("official")) {
                            getLogger()
                                    .error(
                                            "Silk: Expected official namespace in AccessWidener file '{}' inside JAR '{}' (referenced by {}).",
                                            awPath,
                                            ref,
                                            fmjSourceDescription);
                        }

                        var reader = ClassTweakerReader.create(classTweaker);
                        reader.read(data, "official");
                    }
                } catch (IOException | GradleException e) {
                    throw new GradleException(
                            String.format(
                                    "Silk: Failed to load or parse AccessWidener '%s' referenced by %s: %s",
                                    awPath, fmjSourceDescription, e.getMessage()),
                            e);
                }
            }
        }
    }

    private byte[] readEntryData(InputStream inputStream) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }

    private byte[] transformClassBytecode(byte[] originalClassBytes) {
        ClassReader classReader = new ClassReader(originalClassBytes);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);

        classReader.accept(classTweaker.createClassVisitor(Opcodes.ASM9, classWriter, null), ClassReader.EXPAND_FRAMES);
        return classWriter.toByteArray();
    }
}
