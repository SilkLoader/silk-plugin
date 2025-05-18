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
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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

        if (!inputJarFile.exists()) {
            throw new GradleException("Input JAR does not exist: " + inputJarFile.getAbsolutePath());
        }

        File outputDir = outputJarFile.getParentFile();
        if (outputDir != null && !outputDir.exists()) {
            if (!outputDir.mkdirs()) {
                throw new GradleException("Could not create output directory: " + outputDir.getAbsolutePath());
            }
        }

        getLogger().lifecycle("Starting transformation of JAR: {}", inputJarFile.getName());
        Map<String, List<String>> interfaceMappings = new HashMap<>();

        for (File source : getModConfigurationSources()) {
            getLogger().debug("Looking for fabric.mod.json in: {}", source.getAbsolutePath());
            if (!source.exists()) {
                getLogger().debug("Configuration source file does not exist, skipping: {}", source.getAbsolutePath());
                continue;
            }

            if (source.isFile() && source.getName().equals("fabric.mod.json")) {
                getLogger().debug("Loading interface mappings from fabric.mod.json.");
                mergeMappings(interfaceMappings, loadInterfaceMappingsFromFile(source));
            } else if (source.isFile() && source.getName().toLowerCase().endsWith(".jar")) {
                try (JarFile jar = new JarFile(source)) {
                    JarEntry modJsonEntry = jar.getJarEntry("fabric.mod.json");
                    if (modJsonEntry != null) {
                        getLogger()
                                .debug("Loading interface mappings from fabric.mod.json in JAR: {}", source.getName());
                        try (InputStream is = jar.getInputStream(modJsonEntry)) {
                            mergeMappings(
                                    interfaceMappings,
                                    loadInterfaceMappingsFromStream(is, source.getName() + "!fabric.mod.json"));
                        }
                    }
                } catch (IOException e) {
                    getLogger()
                            .warn(
                                    "Could not read fabric.mod.json from dependency JAR: {}",
                                    source.getAbsolutePath(),
                                    e);
                }
            }
        }

        try (JarInputStream jis = new JarInputStream(new BufferedInputStream(new FileInputStream(inputJarFile)));
                JarOutputStream jos =
                        new JarOutputStream(new BufferedOutputStream(new FileOutputStream(outputJarFile)))) {

            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                jos.putNextEntry(new ZipEntry(entry.getName()));

                if (entry.isDirectory()) {
                    jos.closeEntry();
                    continue;
                }

                byte[] entryBytes = readEntryData(jis);

                if (entry.getName().endsWith(".class")) {
                    String classNameInternal =
                            entry.getName().substring(0, entry.getName().length() - ".class".length());
                    List<String> interfacesToAdd = interfaceMappings.get(classNameInternal);

                    if (interfacesToAdd != null && !interfacesToAdd.isEmpty()) {
                        getLogger()
                                .debug(
                                        "Transforming class {}: adding interfaces {}",
                                        classNameInternal,
                                        interfacesToAdd);
                        try {
                            entryBytes = addInterfacesToClassBytecode(entryBytes, interfacesToAdd);
                        } catch (Exception e) {
                            throw new GradleException("Failed to transform class: " + classNameInternal, e);
                        }
                    }
                }
                jos.write(entryBytes);
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new GradleException("Error during JAR transformation process", e);
        }
    }

    private void mergeMappings(Map<String, List<String>> targetMap, Map<String, List<String>> newMappings) {
        newMappings.forEach((className, interfaces) -> {
            targetMap.computeIfAbsent(className, k -> new ArrayList<>()).addAll(interfaces);
            targetMap.put(className, new ArrayList<>(new HashSet<>(targetMap.get(className))));
        });
    }

    private Map<String, List<String>> loadInterfaceMappingsFromFile(File modJsonFile) {
        if (modJsonFile == null || !modJsonFile.exists() || !modJsonFile.isFile()) {
            getLogger()
                    .warn(
                            "Provided mod.json file is invalid or does not exist: {}",
                            modJsonFile != null ? modJsonFile.getAbsolutePath() : "null");
            return Map.of();
        }
        try (InputStream is = new FileInputStream(modJsonFile)) {
            return parseMappingsFromStream(is);
        } catch (IOException e) {
            getLogger()
                    .error("Failed to read mod.json file for interface mappings: {}", modJsonFile.getAbsolutePath(), e);
            return Map.of();
        }
    }

    private Map<String, List<String>> loadInterfaceMappingsFromStream(
            InputStream inputStream, String sourceDescription) {
        try {
            return parseMappingsFromStream(inputStream);
        } catch (IOException e) {
            getLogger()
                    .error(
                            "Failed to parse fabric.mod.json from stream for interface mappings: {}",
                            sourceDescription,
                            e);
            return Map.of();
        }
    }

    private Map<String, List<String>> parseMappingsFromStream(InputStream inputStream) throws IOException {
        JsonNode rootNode = OBJECT_MAPPER.readTree(inputStream);
        JsonNode customNode = rootNode.path("custom");
        if (customNode.isMissingNode() || !customNode.isObject()) {
            return Map.of();
        }

        JsonNode injectionsNode = customNode.path("silk:injected_interfaces");
        if (injectionsNode.isMissingNode() || !injectionsNode.isObject()) {
            return Map.of();
        }

        Set<Map.Entry<String, JsonNode>> fields = injectionsNode.properties();
        Map<String, List<String>> mappings = new HashMap<>();
        for (Map.Entry<String, JsonNode> entry : fields) {
            String className = entry.getKey().replace('.', '/');
            JsonNode interfacesArray = entry.getValue();

            if (interfacesArray.isArray()) {
                List<String> normalizedInterfaceNames = new ArrayList<>();
                for (JsonNode interfaceNode : interfacesArray) {
                    if (interfaceNode.isTextual()) {
                        normalizedInterfaceNames.add(interfaceNode.asText().replace('.', '/'));
                    }
                }

                if (!className.isEmpty() && !normalizedInterfaceNames.isEmpty()) {
                    mappings.computeIfAbsent(className, k -> new ArrayList<>()).addAll(normalizedInterfaceNames);
                }
            }
        }

        mappings.forEach(
                (className, interfaceList) -> mappings.put(className, new ArrayList<>(new HashSet<>(interfaceList))));
        return mappings;
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

    private byte[] addInterfacesToClassBytecode(byte[] originalClassBytes, List<String> interfacesToAddFqns) {
        ClassReader classReader = new ClassReader(originalClassBytes);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);

        ClassVisitor classVisitor = new ClassVisitor(Opcodes.ASM9, classWriter) {
            @Override
            public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] existingInterfaces) {
                Set<String> allInterfaces = new HashSet<>();

                if (existingInterfaces != null) {
                    allInterfaces.addAll(Arrays.asList(existingInterfaces));
                }

                for (String fqn : interfacesToAddFqns) {
                    if (fqn != null && !fqn.trim().isEmpty()) {
                        allInterfaces.add(fqn.replace('.', '/'));
                    }
                }
                String[] newInterfacesArray = allInterfaces.toArray(new String[0]);
                getLogger()
                        .debug(
                                "Class: {}, Original Interfaces: {}, New Interfaces: {}",
                                name,
                                Arrays.toString(existingInterfaces),
                                Arrays.toString(newInterfacesArray));
                super.visit(version, access, name, signature, superName, newInterfacesArray);
            }
        };

        classReader.accept(classVisitor, 0);
        return classWriter.toByteArray();
    }
}
