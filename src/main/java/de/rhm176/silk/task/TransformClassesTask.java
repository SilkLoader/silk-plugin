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
import de.rhm176.silk.accesswidener.*;
import java.io.*;
import java.nio.file.Files;
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
    static final class ProcessedAccessWidener {
        AccessModifier classModifier;
        Map<String, AccessModifier> fieldModifiers = new HashMap<>();
        Map<String, AccessModifier> methodModifiers = new HashMap<>();

        boolean isEmpty() {
            return classModifier == null && fieldModifiers.isEmpty() && methodModifiers.isEmpty();
        }

        void updateClassModifierWithRule(AccessModifier newRuleModifier) {
            if (newRuleModifier == AccessModifier.EXTENDABLE) {
                this.classModifier = AccessModifier.EXTENDABLE;
            } else if (newRuleModifier == AccessModifier.ACCESSIBLE) {
                if (this.classModifier != AccessModifier.EXTENDABLE) {
                    this.classModifier = AccessModifier.ACCESSIBLE;
                }
            }
        }

        void addFieldRule(String name, String desc, AccessModifier modifier) {
            String key = name + ":" + desc;
            AccessModifier current = fieldModifiers.get(key);
            if (modifier == AccessModifier.MUTABLE) {
                fieldModifiers.put(key, AccessModifier.MUTABLE);
            } else if (modifier == AccessModifier.ACCESSIBLE) {
                if (current != AccessModifier.MUTABLE) {
                    fieldModifiers.put(key, AccessModifier.ACCESSIBLE);
                }
            }
        }

        void addMethodRule(String name, String desc, AccessModifier modifier) {
            String key = name + desc;
            AccessModifier current = methodModifiers.get(key);
            if (modifier == AccessModifier.EXTENDABLE) {
                methodModifiers.put(key, AccessModifier.EXTENDABLE);
            } else if (modifier == AccessModifier.ACCESSIBLE) {
                if (current != AccessModifier.EXTENDABLE) {
                    methodModifiers.put(key, AccessModifier.ACCESSIBLE);
                }
            }
        }
    }

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

    private Map<String, ProcessedAccessWidener> processRawAwRules(List<AccessWidenerRule> rawRules) {
        Map<String, ProcessedAccessWidener> organizedRules = new HashMap<>();
        if (rawRules == null) return organizedRules;

        for (AccessWidenerRule rule : rawRules) {
            String classNameInternal = rule.getClassName();
            AccessModifier modifier = rule.getModifier();

            if (rule instanceof ClassAccessWidener) {
                ProcessedAccessWidener classRules =
                        organizedRules.computeIfAbsent(classNameInternal, k -> new ProcessedAccessWidener());
                classRules.updateClassModifierWithRule(modifier);
            } else if (rule instanceof MethodAccessWidener methodWidener) {
                ProcessedAccessWidener classRules =
                        organizedRules.computeIfAbsent(classNameInternal, k -> new ProcessedAccessWidener());
                classRules.addMethodRule(methodWidener.getMethodName(), methodWidener.getMethodDescriptor(), modifier);
            } else if (rule instanceof FieldAccessWidener fieldWidener) {
                ProcessedAccessWidener classRules =
                        organizedRules.computeIfAbsent(classNameInternal, k -> new ProcessedAccessWidener());
                classRules.addFieldRule(fieldWidener.getFieldName(), fieldWidener.getFieldDescriptor(), modifier);
            }
        }

        for (ProcessedAccessWidener classRules : organizedRules.values()) {
            for (AccessModifier fieldMod : classRules.fieldModifiers.values()) {
                if (fieldMod == AccessModifier.ACCESSIBLE || fieldMod == AccessModifier.MUTABLE) {
                    classRules.updateClassModifierWithRule(AccessModifier.ACCESSIBLE);
                }
            }
            for (AccessModifier methodMod : classRules.methodModifiers.values()) {
                if (methodMod == AccessModifier.ACCESSIBLE) {
                    classRules.updateClassModifierWithRule(AccessModifier.ACCESSIBLE);
                } else if (methodMod == AccessModifier.EXTENDABLE) {
                    classRules.updateClassModifierWithRule(AccessModifier.EXTENDABLE);
                }
            }
        }
        return organizedRules;
    }

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

        Map<String, List<String>> interfaceMappings = new HashMap<>();
        List<AccessWidenerRule> allRawAwRulesList = new ArrayList<>();

        for (File sourceFileOrJar : getModConfigurationSources().getFiles().stream()
                .sorted(Comparator.comparing(File::getAbsolutePath))
                .toList()) {
            if (!sourceFileOrJar.exists()) continue;

            if (sourceFileOrJar.isFile() && sourceFileOrJar.getName().equals("fabric.mod.json")) {
                try (InputStream is = new FileInputStream(sourceFileOrJar)) {
                    processFabricModJsonStream(
                            is, sourceFileOrJar.getAbsolutePath(), interfaceMappings, allRawAwRulesList, null);
                } catch (IOException e) {
                    getLogger()
                            .error(
                                    "Silk: Failed to read direct fabric.mod.json: {}",
                                    sourceFileOrJar.getAbsolutePath(),
                                    e);
                }
            } else if (sourceFileOrJar.isFile()
                    && sourceFileOrJar.getName().toLowerCase().endsWith(".jar")) {
                try (JarFile jar = new JarFile(sourceFileOrJar)) {
                    JarEntry fmjEntry = jar.getJarEntry("fabric.mod.json");
                    if (fmjEntry != null) {
                        try (InputStream is = jar.getInputStream(fmjEntry)) {
                            processFabricModJsonStream(
                                    is,
                                    sourceFileOrJar.getName() + "!fabric.mod.json",
                                    interfaceMappings,
                                    allRawAwRulesList,
                                    jar);
                        }
                    }
                } catch (IOException e) {
                    getLogger().warn("Silk: Could not read JAR: {}", sourceFileOrJar.getAbsolutePath(), e);
                }
            }
        }

        Map<String, ProcessedAccessWidener> aggregatedProcessedAwRules =
                allRawAwRulesList.isEmpty() ? Collections.emptyMap() : processRawAwRules(allRawAwRulesList);
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
                    List<String> interfacesToAdd = interfaceMappings.get(classNameInternal);
                    ProcessedAccessWidener awRules =
                            aggregatedProcessedAwRules.getOrDefault(classNameInternal, new ProcessedAccessWidener());

                    if ((interfacesToAdd != null && !interfacesToAdd.isEmpty()) || !awRules.isEmpty()) {
                        getLogger()
                                .debug(
                                        "Silk: Transforming class {}: adding interfaces {} and applying AW rules.",
                                        classNameInternal,
                                        Objects.toString(interfacesToAdd, "none"));
                        try {
                            entryBytes = transformClassBytecode(entryBytes, interfacesToAdd, awRules);
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
            targetMap.put(className, targetMap.get(className).stream().sorted().toList());
        });
    }

    /**
     * Processes a single fabric.mod.json stream to extract interface mappings and AW file info.
     *
     * @param fmjStream InputStream of the fabric.mod.json content.
     * @param fmjSourceDescription Description of where this fmjStream comes from (for logging).
     * @param outInterfaceMappings Map to add parsed interface mappings to.
     * @param outRawAwRulesList List to add parsed AccessWidenerRules to.
     * @param containingJar Optional JarFile if the fmjStream comes from within a JAR, used to resolve AW paths.
     */
    private void processFabricModJsonStream(
            InputStream fmjStream,
            String fmjSourceDescription,
            Map<String, List<String>> outInterfaceMappings,
            List<AccessWidenerRule> outRawAwRulesList,
            JarFile containingJar)
            throws IOException {
        JsonNode rootNode = OBJECT_MAPPER.readTree(fmjStream);

        JsonNode customNode = rootNode.path("custom");
        if (customNode.isObject()) {
            JsonNode injectionsNode = customNode.path("silk:injected_interfaces");
            if (injectionsNode.isObject()) {
                Map<String, List<String>> currentFileMappings = new HashMap<>();
                Set<Map.Entry<String, JsonNode>> fields = injectionsNode.properties();
                for (Map.Entry<String, JsonNode> field : fields) {
                    String className = field.getKey().replace('.', '/');
                    JsonNode interfacesArray = field.getValue();
                    if (interfacesArray.isArray()) {
                        List<String> normalizedInterfaceNames = new ArrayList<>();
                        for (JsonNode interfaceNode : interfacesArray) {
                            if (interfaceNode.isTextual()) {
                                normalizedInterfaceNames.add(
                                        interfaceNode.asText().replace('.', '/'));
                            }
                        }
                        Collections.sort(normalizedInterfaceNames);
                        if (!className.isEmpty() && !normalizedInterfaceNames.isEmpty()) {
                            currentFileMappings
                                    .computeIfAbsent(className, k -> new ArrayList<>())
                                    .addAll(normalizedInterfaceNames);
                        }
                    }
                }

                currentFileMappings.forEach((cn, il) ->
                        currentFileMappings.put(cn, il.stream().sorted().toList()));
                mergeMappings(outInterfaceMappings, currentFileMappings);
            }
        }

        JsonNode awPathNode = rootNode.path("accessWidener");
        if (awPathNode.isTextual()) {
            String awPath = awPathNode.asText();
            if (awPath != null && !awPath.trim().isEmpty()) {
                try {
                    AccessWidener widener = null;
                    if (containingJar != null) {
                        JarEntry awEntry = containingJar.getJarEntry(awPath);
                        if (awEntry != null) {
                            try (InputStream awStream = containingJar.getInputStream(awEntry)) {
                                widener = new AccessWidener(awStream, containingJar.getName() + "!" + awPath);
                            }
                        } else {
                            getLogger()
                                    .warn(
                                            "Silk: AccessWidener file '{}' not found inside JAR '{}' (referenced by {}).",
                                            awPath,
                                            containingJar.getName(),
                                            fmjSourceDescription);
                        }
                    } else {
                        File fmjFile = new File(fmjSourceDescription);
                        File awFile = new File(fmjFile.getParentFile(), awPath);
                        if (awFile.exists() && awFile.isFile()) {
                            widener =
                                    new AccessWidener(Files.newInputStream(awFile.toPath()), awFile.getAbsolutePath());
                        } else {
                            getLogger()
                                    .warn(
                                            "AccessWidener file '{}' (resolved to '{}') not found on filesystem (referenced by {}).",
                                            awPath,
                                            awFile.getAbsolutePath(),
                                            fmjSourceDescription);
                        }
                    }

                    if (widener != null) {
                        outRawAwRulesList.addAll(widener.getRules());
                    }
                } catch (IOException | GradleException e) {
                    getLogger()
                            .error(
                                    "Silk: Failed to load or parse AccessWidener '{}' referenced by {}: {}",
                                    awPath,
                                    fmjSourceDescription,
                                    e.getMessage());
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

    private byte[] transformClassBytecode(
            byte[] originalClassBytes,
            List<String> interfacesToAddInternalNames,
            ProcessedAccessWidener awRulesForThisClass) {
        ClassReader classReader = new ClassReader(originalClassBytes);
        ClassWriter classWriter = new ClassWriter(classReader, ClassWriter.COMPUTE_FRAMES);

        ClassVisitor transformingVisitor = new ClassVisitor(Opcodes.ASM9, classWriter) {
            @Override
            public void visit(
                    int version,
                    int access,
                    String name,
                    String signature,
                    String superName,
                    String[] existingInterfaces) {
                int newAccess = access;
                if (awRulesForThisClass != null && awRulesForThisClass.classModifier != null) {
                    newAccess = applyClassAccessModifier(access, awRulesForThisClass.classModifier);
                }

                Set<String> combinedInterfacesSet = new HashSet<>();
                if (existingInterfaces != null) {
                    combinedInterfacesSet.addAll(Arrays.asList(existingInterfaces));
                }
                if (interfacesToAddInternalNames != null) {
                    for (String internalName : interfacesToAddInternalNames) {
                        if (internalName != null && !internalName.trim().isEmpty()) {
                            combinedInterfacesSet.add(internalName);
                        }
                    }
                }

                super.visit(
                        version,
                        newAccess,
                        name,
                        signature,
                        superName,
                        combinedInterfacesSet.stream().sorted().toArray(String[]::new));
            }

            @Override
            public FieldVisitor visitField(
                    int access, String fieldName, String fieldDescriptor, String signature, Object value) {
                int newAccess = access;
                if (awRulesForThisClass != null) {
                    AccessModifier fieldAwModifier =
                            awRulesForThisClass.fieldModifiers.get(fieldName + ":" + fieldDescriptor);
                    if (fieldAwModifier != null) {
                        newAccess = applyFieldAccessModifier(access, fieldAwModifier);
                    }
                }
                return super.visitField(newAccess, fieldName, fieldDescriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(
                    int access, String methodName, String methodDescriptor, String signature, String[] exceptions) {
                int newAccess = access;
                if (awRulesForThisClass != null) {
                    AccessModifier methodAwModifier =
                            awRulesForThisClass.methodModifiers.get(methodName + methodDescriptor);
                    if (methodAwModifier != null) {
                        newAccess = applyMethodAccessModifier(access, methodAwModifier);
                    }
                }
                return super.visitMethod(newAccess, methodName, methodDescriptor, signature, exceptions);
            }
        };

        classReader.accept(transformingVisitor, ClassReader.EXPAND_FRAMES);
        return classWriter.toByteArray();
    }

    private int applyClassAccessModifier(int currentAccess, AccessModifier awModifier) {
        if (awModifier == AccessModifier.ACCESSIBLE) {
            return (currentAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        } else if (awModifier == AccessModifier.EXTENDABLE) {
            int acc = (currentAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            return acc & ~Opcodes.ACC_FINAL;
        }
        return currentAccess;
    }

    private int applyFieldAccessModifier(int currentAccess, AccessModifier awModifier) {
        int newAccess = currentAccess;
        if (awModifier == AccessModifier.MUTABLE) {
            newAccess &= ~Opcodes.ACC_FINAL;
            newAccess = (newAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        } else if (awModifier == AccessModifier.ACCESSIBLE) {
            newAccess = (newAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }
        return newAccess;
    }

    private int applyMethodAccessModifier(int currentAccess, AccessModifier awModifier) {
        int newAccess = currentAccess;
        if (awModifier == AccessModifier.ACCESSIBLE) {
            newAccess = (newAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        } else if (awModifier == AccessModifier.EXTENDABLE) {
            newAccess &= ~Opcodes.ACC_FINAL;
            if ((newAccess & Opcodes.ACC_STATIC) != 0) {
                newAccess = (newAccess & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
            } else {
                if ((newAccess & Opcodes.ACC_PUBLIC) == 0) {
                    newAccess = (newAccess & ~Opcodes.ACC_PRIVATE) | Opcodes.ACC_PROTECTED;
                }
            }
        }
        return newAccess;
    }
}
