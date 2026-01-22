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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

class TransformClassesTaskTest {

    @TempDir
    Path projectDir;

    private File buildFile;
    private File inputJarFile;
    private File outputJarFile;

    @BeforeEach
    void setUp() throws IOException {
        buildFile = projectDir.resolve("build.gradle").toFile();
        inputJarFile = projectDir.resolve("input.jar").toFile();
        outputJarFile = projectDir.resolve("output.jar").toFile();

        String buildGradleContent =
                """
        plugins {
            id 'de.rhm176.silk.silk-plugin'
        }

        File dummyGameJar = new File(project.projectDir, "dummy-game.jar")
        dummyGameJar.createNewFile()

        silk {
            silkLoaderCoordinates = ""
        }

        dependencies {
            equilinox files('dummy-game.jar')
        }


        """;
        Files.writeString(buildFile.toPath(), buildGradleContent, StandardCharsets.UTF_8);

        File dummyGameJarFile = projectDir.resolve("dummy-game.jar").toFile();
        if (!dummyGameJarFile.exists()) {
            try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(dummyGameJarFile))) {
                jos.putNextEntry(new JarEntry("dummy.txt"));
                jos.write("dummy content".getBytes());
                jos.closeEntry();
            }
        }
    }

    private void writeBuildGradleWithTaskConfig(
            String inputJarPath, String outputJarPath, List<String> modConfigSourcesPaths) throws IOException {
        String inputJarPathEscaped = inputJarPath.replace("\\", "\\\\");
        String outputJarPathEscaped = outputJarPath.replace("\\", "\\\\");

        StringBuilder taskConfigBuilder = new StringBuilder();
        taskConfigBuilder.append(String.format(
                """

                tasks.named('%s', %s).configure { task ->
                    task.enabled = false
                }

                tasks.named('%s', %s) {
                    inputJar.set(project.file("%s"))
                    outputTransformedJar.set(project.file("%s"))
                """,
                SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME,
                ModifyFabricModJsonTask.class.getName(),
                SilkPlugin.TRANSFORM_CLASSES_TASK_NAME,
                "de.rhm176.silk.task.TransformClassesTask",
                inputJarPathEscaped,
                outputJarPathEscaped));

        if (modConfigSourcesPaths != null && !modConfigSourcesPaths.isEmpty()) {
            String modConfigSourcesCode = modConfigSourcesPaths.stream()
                    .map(s -> "project.file(\"" + s.replace("\\", "\\\\") + "\")")
                    .collect(Collectors.joining(", "));

            taskConfigBuilder.append(String.format(
                    """
                        modConfigurationSources.from(%s)
                    """,
                    modConfigSourcesCode));
        }
        taskConfigBuilder.append("}\n");

        String currentBuildContent = Files.readString(buildFile.toPath());
        Files.writeString(buildFile.toPath(), currentBuildContent + taskConfigBuilder, StandardCharsets.UTF_8);
    }

    private GradleRunner createRunner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath();
    }

    private void assertTaskOutcome(TaskOutcome expectedOutcome, BuildTask task) {
        assertNotNull(task);
        assertEquals(expectedOutcome, task.getOutcome());
    }

    private byte[] createDummyClass(String className, String... interfaces) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, className.replace('.', '/'), null, "java/lang/Object", interfaces);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createDummyClassWithMembers(
            String className,
            int classAccess,
            String superName,
            String[] interfaces,
            List<FieldTestData> fields,
            List<MethodTestData> methods) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, classAccess, className.replace('.', '/'), null, superName.replace('.', '/'), interfaces);

        if (fields != null) {
            for (FieldTestData field : fields) {
                cw.visitField(field.access, field.name, field.descriptor, null, null)
                        .visitEnd();
            }
        }

        if (methods != null) {
            for (MethodTestData method : methods) {
                MethodVisitor mv = cw.visitMethod(method.access, method.name, method.descriptor, null, null);
                mv.visitCode();
                if (!method.descriptor.endsWith("V")) {
                    if (method.descriptor.endsWith("I;")
                            || method.descriptor.endsWith("Z;")
                            || method.descriptor.endsWith("C;")
                            || method.descriptor.endsWith("S;")
                            || method.descriptor.endsWith("B;")) {
                        mv.visitInsn(Opcodes.ICONST_0);
                        mv.visitInsn(Opcodes.IRETURN);
                    } else if (method.descriptor.endsWith("J;")) {
                        mv.visitInsn(Opcodes.LCONST_0);
                        mv.visitInsn(Opcodes.LRETURN);
                    } else if (method.descriptor.endsWith("F;")) {
                        mv.visitInsn(Opcodes.FCONST_0);
                        mv.visitInsn(Opcodes.FRETURN);
                    } else if (method.descriptor.endsWith("D;")) {
                        mv.visitInsn(Opcodes.DCONST_0);
                        mv.visitInsn(Opcodes.DRETURN);
                    } else {
                        mv.visitInsn(Opcodes.ACONST_NULL);
                        mv.visitInsn(Opcodes.ARETURN);
                    }
                } else {
                    mv.visitInsn(Opcodes.RETURN);
                }
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    private void createJar(File jarFile, Map<String, byte[]> entries) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jos.putNextEntry(jarEntry);
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }
    }

    private ClassNode readClassFromJar(File jarFile, String classNamePath) throws IOException {
        try (JarInputStream jis = new JarInputStream(new FileInputStream(jarFile))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.getName().equals(classNamePath)) {
                    ClassReader cr = new ClassReader(jis.readAllBytes());
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, 0);
                    return cn;
                }
            }
        }
        throw new FileNotFoundException("Class " + classNamePath + " not found in " + jarFile.getName());
    }

    static class FieldTestData {
        int access;
        String name;
        String descriptor;

        FieldTestData(int access, String name, String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    static class MethodTestData {
        int access;
        String name;
        String descriptor;

        MethodTestData(int access, String name, String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    @Test
    @DisplayName("AccessWidener: Should make a private class public (accessible)")
    void testAccessWidener_makeClassAccessible() throws IOException {
        String targetClassName = "com/example/PrivateClass";
        Map<String, byte[]> inputJarEntries = new HashMap<>();
        inputJarEntries.put(
                targetClassName + ".class",
                createDummyClassWithMembers(
                        targetClassName, Opcodes.ACC_PRIVATE, "java/lang/Object", null, null, null));
        createJar(inputJarFile, inputJarEntries);

        File modJsonFile = projectDir.resolve("fabric.mod.json").toFile();
        File awFile = projectDir.resolve("test.accesswidener").toFile();

        String modJsonContent =
                """
            {
              "schemaVersion": 1,
              "id": "awmod",
              "accessWidener": "test.accesswidener"
            }
            """;
        Files.writeString(modJsonFile.toPath(), modJsonContent, StandardCharsets.UTF_8);

        String awContent = String.format(
                """
            classTweaker v1 official
            accessible class %s
            """,
                targetClassName);
        Files.writeString(awFile.toPath(), awContent, StandardCharsets.UTF_8);

        writeBuildGradleWithTaskConfig(inputJarFile.getName(), outputJarFile.getName(), List.of(modJsonFile.getName()));

        BuildResult result = createRunner(SilkPlugin.TRANSFORM_CLASSES_TASK_NAME, "--stacktrace")
                .build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.TRANSFORM_CLASSES_TASK_NAME));

        assertTrue(outputJarFile.exists());
        ClassNode cn = readClassFromJar(outputJarFile, targetClassName + ".class");
        assertTrue((cn.access & Opcodes.ACC_PUBLIC) != 0, "Class should be public");
        assertFalse((cn.access & Opcodes.ACC_PRIVATE) != 0, "Class should not be private");
    }

    @Test
    @DisplayName("AccessWidener: Should make a private final field public and mutable")
    void testAccessWidener_makeFieldMutableAndAccessible() throws IOException {
        String targetClassName = "com/example/FieldHolder";
        String fieldName = "myFinalField";
        String fieldDesc = "I";

        Map<String, byte[]> inputJarEntries = new HashMap<>();
        inputJarEntries.put(
                targetClassName + ".class",
                createDummyClassWithMembers(
                        targetClassName,
                        Opcodes.ACC_PUBLIC,
                        "java/lang/Object",
                        null,
                        List.of(new FieldTestData(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, fieldName, fieldDesc)),
                        null));
        createJar(inputJarFile, inputJarEntries);

        File modJsonFile = projectDir.resolve("fabric.mod.json").toFile();
        File awFile = projectDir.resolve("field_test.accesswidener").toFile();

        String modJsonContent =
                """
            {
              "schemaVersion": 1,
              "id": "fieldawmod",
              "accessWidener": "field_test.accesswidener"
            }
            """;
        Files.writeString(modJsonFile.toPath(), modJsonContent, StandardCharsets.UTF_8);

        String awContent = String.format(
                """
            classTweaker v1 official
            accessible field %s %s %s
            mutable field %s %s %s
            """,
                targetClassName, fieldName, fieldDesc, targetClassName, fieldName, fieldDesc);
        Files.writeString(awFile.toPath(), awContent, StandardCharsets.UTF_8);

        writeBuildGradleWithTaskConfig(inputJarFile.getName(), outputJarFile.getName(), List.of(modJsonFile.getName()));

        BuildResult result = createRunner(SilkPlugin.TRANSFORM_CLASSES_TASK_NAME, "--stacktrace")
                .build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.TRANSFORM_CLASSES_TASK_NAME));

        assertTrue(outputJarFile.exists());
        ClassNode cn = readClassFromJar(outputJarFile, targetClassName + ".class");
        assertNotNull(cn.fields);
        FieldNode fn = cn.fields.stream()
                .filter(f -> f.name.equals(fieldName))
                .findFirst()
                .orElse(null);
        assertNotNull(fn, "Field " + fieldName + " not found.");

        assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0, "Field should be public");
        assertFalse((fn.access & Opcodes.ACC_PRIVATE) != 0, "Field should not be private");
        assertFalse((fn.access & Opcodes.ACC_FINAL) != 0, "Field should not be final");
    }

    @Test
    @DisplayName("Should correctly apply both interface injection and AccessWidener (extendable class) rules")
    void testCombinedInterfaceAndAccessWidener() throws IOException {
        String targetClassName = "com/example/CombinedTarget";
        String interfaceName = "com/example/AnotherInterface";

        Map<String, byte[]> inputJarEntries = new HashMap<>();
        inputJarEntries.put(
                targetClassName + ".class",
                createDummyClassWithMembers(
                        targetClassName,
                        Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        "java/lang/Object",
                        null,
                        null,
                        null));
        createJar(inputJarFile, inputJarEntries);

        File modJsonFile = projectDir.resolve("fabric.mod.json").toFile();
        File awFile = projectDir.resolve("combined.ct").toFile();

        String modJsonContent = String.format(
                """
            {
              "schemaVersion": 1,
              "id": "combinedmod",
              "accessWidener": "%s"
            }
            """, awFile.getName());
        Files.writeString(modJsonFile.toPath(), modJsonContent, StandardCharsets.UTF_8);

        String awContent = String.format(
                """
            classTweaker v1 official
            extendable class %s
            inject-interface %s %s
            """,
                targetClassName, targetClassName, interfaceName);
        Files.writeString(awFile.toPath(), awContent, StandardCharsets.UTF_8);

        writeBuildGradleWithTaskConfig(inputJarFile.getName(), outputJarFile.getName(), List.of(modJsonFile.getName()));

        BuildResult result = createRunner(SilkPlugin.TRANSFORM_CLASSES_TASK_NAME, "--stacktrace")
                .build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.TRANSFORM_CLASSES_TASK_NAME));

        assertTrue(outputJarFile.exists());
        ClassNode cn = readClassFromJar(outputJarFile, targetClassName + ".class");

        assertTrue(cn.interfaces.contains(interfaceName), "Class should implement " + interfaceName);

        assertTrue((cn.access & Opcodes.ACC_PUBLIC) != 0, "Class should be public");
        assertFalse((cn.access & Opcodes.ACC_PRIVATE) != 0, "Class should not be private");
        assertFalse((cn.access & Opcodes.ACC_FINAL) != 0, "Class should not be final");
    }

    @Test
    @DisplayName("No applicable rules: Should copy the input JAR as is without transformations")
    void testNoApplicableRules_shouldCopyJarAsIs() throws IOException {
        String className = "com/example/NoChangeClass";
        byte[] classBytes = createDummyClass(className);
        Map<String, byte[]> inputJarEntries = new HashMap<>();
        inputJarEntries.put(className + ".class", classBytes);
        inputJarEntries.put("some/other/resource.txt", "TestData".getBytes(StandardCharsets.UTF_8));
        createJar(inputJarFile, inputJarEntries);

        File modJsonFile = projectDir.resolve("empty.fabric.mod.json").toFile();
        String modJsonContent =
                """
            {
              "schemaVersion": 1,
              "id": "emptymod",
              "custom": {
                "silk:injected_interfaces": {}
              }
            }
            """;
        Files.writeString(modJsonFile.toPath(), modJsonContent, StandardCharsets.UTF_8);

        writeBuildGradleWithTaskConfig(inputJarFile.getName(), outputJarFile.getName(), List.of(modJsonFile.getName()));

        BuildResult result = createRunner(SilkPlugin.TRANSFORM_CLASSES_TASK_NAME, "--stacktrace")
                .build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.TRANSFORM_CLASSES_TASK_NAME));

        assertTrue(outputJarFile.exists());

        ClassNode cn = readClassFromJar(outputJarFile, className + ".class");
        assertEquals(0, cn.interfaces == null ? 0 : cn.interfaces.size(), "Class should have no new interfaces");

        boolean resourceFound = false;
        try (JarInputStream jis = new JarInputStream(new FileInputStream(outputJarFile))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (entry.getName().equals("some/other/resource.txt")) {
                    resourceFound = true;
                    String content = new String(jis.readAllBytes(), StandardCharsets.UTF_8);
                    assertEquals("TestData", content);
                    break;
                }
            }
        }
        assertTrue(resourceFound, "Non-class resource was not copied.");
    }

    @Test
    @DisplayName("AccessWidener file with only header: Should succeed with no rules applied")
    void testAwFileWithOnlyHeader_SucceedsWithNoRules() throws IOException {
        String targetClassName = "com/example/TargetClass";
        byte[] originalBytes = createDummyClass(targetClassName);
        Map<String, byte[]> inputJarEntries = new HashMap<>();
        inputJarEntries.put(targetClassName + ".class", originalBytes);
        createJar(inputJarFile, inputJarEntries);

        File modJsonFile = projectDir.resolve("mod_header_only_aw.json").toFile();
        File awFile = projectDir.resolve("header_only.accesswidener").toFile();
        String modJsonContent = String.format(
                """
            {
              "schemaVersion": 1, "id": "headeronlymod", "accessWidener": "%s"
            }
            """,
                awFile.getName());
        Files.writeString(modJsonFile.toPath(), modJsonContent, StandardCharsets.UTF_8);

        String awContent = "classTweaker v1 official";
        Files.writeString(awFile.toPath(), awContent, StandardCharsets.UTF_8);

        writeBuildGradleWithTaskConfig(inputJarFile.getName(), outputJarFile.getName(), List.of(modJsonFile.getName()));
        BuildResult result = createRunner(SilkPlugin.TRANSFORM_CLASSES_TASK_NAME, "--stacktrace")
                .build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.TRANSFORM_CLASSES_TASK_NAME));

        ClassNode cn = readClassFromJar(outputJarFile, targetClassName + ".class");
        assertEquals(
                Opcodes.ACC_PUBLIC,
                cn.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_FINAL),
                "Class access should remain default public");
    }

    @Test
    @DisplayName(
            "AccessWidener file referenced in a JAR mod is not found: Should warn and succeed without applying those AW rules")
    void testAwFileReferencedInJarModNotFound_WarnsAndSucceeds() throws IOException {
        String targetClassName = "com/example/AnyClass";
        Map<String, byte[]> inputJarEntries = new HashMap<>();
        inputJarEntries.put(targetClassName + ".class", createDummyClass(targetClassName));
        createJar(inputJarFile, inputJarEntries);

        File modJarWithMissingAw = projectDir.resolve("mod_missing_aw_ref.jar").toFile();
        Map<String, byte[]> modJarEntries = new HashMap<>();
        String modJsonContent =
                """
            {
              "schemaVersion": 1, "id": "missingawmod", "accessWidener": "nonexistent.accesswidener"
            }
            """;
        modJarEntries.put("fabric.mod.json", modJsonContent.getBytes(StandardCharsets.UTF_8));
        createJar(modJarWithMissingAw, modJarEntries);

        writeBuildGradleWithTaskConfig(
                inputJarFile.getName(), outputJarFile.getName(), List.of(modJarWithMissingAw.getName()));

        BuildResult result =
                createRunner(SilkPlugin.TRANSFORM_CLASSES_TASK_NAME, "--info").build();
        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.TRANSFORM_CLASSES_TASK_NAME));
        assertTrue(
                result.getOutput().contains("AccessWidener file 'nonexistent.accesswidener' not found inside JAR"),
                "Should warn about missing AW file in JAR.");

        ClassNode cn = readClassFromJar(outputJarFile, targetClassName + ".class");
        assertTrue((cn.access & Opcodes.ACC_PUBLIC) != 0, "Class should remain public (default from createDummyClass)");
    }
}
