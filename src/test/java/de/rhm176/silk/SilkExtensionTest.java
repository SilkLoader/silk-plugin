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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import com.google.common.jimfs.Jimfs;
import de.rhm176.silk.extension.FabricExtension;
import de.rhm176.silk.extension.VineflowerExtension;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.Provider;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;

class SilkExtensionTest {
    @TempDir
    Path projectDir;

    private Project project;
    private SilkExtension silkExtension;

    private FileSystem jimfs;

    private Path createDummyJarInJimfs(String jarPathStr, List<String> entryNames) throws IOException {
        Path jarPath = jimfs.getPath(jarPathStr);
        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            if (entryNames != null) {
                for (String entryName : entryNames) {
                    jos.putNextEntry(new JarEntry(entryName));
                    jos.write(("content of " + entryName).getBytes(StandardCharsets.UTF_8));
                    jos.closeEntry();
                }
            }
        }
        return jarPath;
    }

    private File createTemporaryGameJar(Path baseDir, String jarName, List<String> entries) throws IOException {
        Path jarFile = baseDir.resolve(jarName);
        Files.createDirectories(jarFile.getParent());
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarFile.toFile()))) {
            if (entries != null) {
                for (String entry : entries) {
                    jos.putNextEntry(new JarEntry(entry));
                    jos.closeEntry();
                }
            }
        }
        return jarFile.toFile();
    }

    @BeforeEach
    void setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir.toFile()).build();
        project.getPluginManager().apply("java");
        silkExtension = project.getExtensions().create("silk", SilkExtension.class);

        jimfs = Jimfs.newFileSystem(com.google.common.jimfs.Configuration.unix());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (jimfs != null) {
            jimfs.close();
        }
    }

    @Test
    @DisplayName(SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME + " property defaults to false")
    void generateFabricModJson_defaultsToFalse() {
        assertFalse(silkExtension.getGenerateFabricModJson().get());
    }

    @Test
    @DisplayName("verifyFabricModJson property defaults to true")
    void verifyFabricModJson_defaultsToTrue() {
        assertTrue(silkExtension.getVerifyFabricModJson().get());
    }

    @Test
    @DisplayName("ModRegistration registers a subproject")
    void modRegistration_registersSubproject() {
        Project subProjectA =
                ProjectBuilder.builder().withName("subA").withParent(project).build();

        silkExtension.getMods().register(subProjectA);

        assertTrue(silkExtension.getRegisteredSubprojectsInternal().contains(subProjectA));
        assertEquals(1, silkExtension.getRegisteredSubprojectsInternal().size());
    }

    @Test
    @DisplayName("ModRegistration handles duplicate subproject registration")
    void modRegistration_handlesDuplicateRegistration() {
        Project subProjectA =
                ProjectBuilder.builder().withName("subA").withParent(project).build();

        silkExtension.getMods().register(subProjectA);
        silkExtension.getMods().register(subProjectA);

        assertEquals(
                1,
                silkExtension.getRegisteredSubprojectsInternal().size(),
                "Should only contain one instance of subProjectA");
    }

    @Test
    @DisplayName("ModRegistration handles null subproject registration")
    void modRegistration_handlesNullRegistration() {
        assertDoesNotThrow(() -> silkExtension.getMods().register(null));
        assertTrue(silkExtension.getRegisteredSubprojectsInternal().isEmpty(), "No subproject should be registered");
    }

    @Test
    @DisplayName("mods(Action) configuration closure works")
    @SuppressWarnings("unchecked")
    void modsAction_configuresMods() {
        Action<SilkExtension.ModRegistration> mockAction = mock(Action.class);
        silkExtension.mods(mockAction);
        verify(mockAction).execute(any(SilkExtension.ModRegistration.class));
    }

    @Test
    @DisplayName("FabricExtension is created and configurable")
    @SuppressWarnings("unchecked")
    void fabricExtension_isCreatedAndConfigurable() {
        assertNotNull(silkExtension.getFabric());
        Action<FabricExtension> mockAction = mock(Action.class);
        silkExtension.fabric(mockAction);
        verify(mockAction).execute(any(FabricExtension.class));
    }

    @Test
    @DisplayName("VineflowerExtension is created and configurable")
    @SuppressWarnings("unchecked")
    void vineflowerExtension_isCreatedAndConfigurable() {
        assertNotNull(silkExtension.getVineflower());
        Action<VineflowerExtension> mockAction = mock(Action.class);
        silkExtension.vineflower(mockAction);
        verify(mockAction).execute(any(VineflowerExtension.class));
    }

    @Test
    @DisplayName("getGameJar throws IllegalStateException if not initialized")
    void getGameJar_throwsExceptionIfNotInitialized() {
        Exception exception = assertThrows(IllegalStateException.class, () -> silkExtension.getGameJar());
        assertTrue(exception.getMessage().contains("SilkExtension.internalGameJarProvider has not been initialized"));
    }

    @Test
    @DisplayName("initializeGameJarProvider sets up gameJar provider correctly from single file configuration")
    void initializeGameJarProvider_singleFile() throws IOException {
        File fakeGameJarFile = projectDir.resolve("EquilinoxTest.jar").toFile();
        assertTrue(fakeGameJarFile.createNewFile(), "Could not create fake game jar");

        Configuration mockEquilinoxConfig = mock(Configuration.class);
        when(mockEquilinoxConfig.getFiles()).thenReturn(Collections.singleton(fakeGameJarFile));
        when(mockEquilinoxConfig.getName()).thenReturn("equilinoxTestConfig");

        silkExtension.initializeGameJarProvider(mockEquilinoxConfig, project);
        Provider<RegularFile> gameJarProvider = silkExtension.getGameJar();

        assertNotNull(gameJarProvider);
        assertTrue(gameJarProvider.isPresent());
        assertEquals(
                fakeGameJarFile.getName(), gameJarProvider.get().getAsFile().getName());
        assertEquals(
                fakeGameJarFile.getAbsolutePath(),
                gameJarProvider.get().getAsFile().getAbsolutePath());
    }

    @Test
    @DisplayName("initializeGameJarProvider handles empty configuration")
    void initializeGameJarProvider_emptyConfig() {
        Configuration mockEquilinoxConfig = mock(Configuration.class);
        when(mockEquilinoxConfig.getFiles()).thenReturn(Collections.emptySet());
        when(mockEquilinoxConfig.getName()).thenReturn("emptyEquilinoxConfig");

        silkExtension.initializeGameJarProvider(mockEquilinoxConfig, project);
        Provider<RegularFile> gameJarProvider = silkExtension.getGameJar();

        assertNotNull(gameJarProvider);
        assertFalse(gameJarProvider.isPresent(), "Game JAR provider should be empty if configuration has no files");
    }

    @Test
    @DisplayName("isCorrectGameJarByContent identifies correct JAR")
    void isCorrectGameJarByContent_identifiesCorrectly() throws IOException {
        Path correctJar = createDummyJarInJimfs("/game/Equilinox.jar", SilkExtension.EQUILINOX_CLASS_FILES);
        Path incompleteJar = createDummyJarInJimfs("/game/Incomplete.jar", List.of("main/MainApp.class"));
        Path wrongJar = createDummyJarInJimfs("/game/Wrong.jar", List.of("another/Thing.class"));
        Path notAJar = jimfs.getPath("/game/notajar.txt");
        Files.writeString(notAJar, "hello");
        Path emptyJar = createDummyJarInJimfs("/game/Empty.jar", null);

        assertTrue(
                silkExtension.isCorrectGameJarByContent(correctJar), "Should identify JAR with all required classes");
        assertFalse(
                silkExtension.isCorrectGameJarByContent(incompleteJar), "Should not identify JAR with missing classes");
        assertFalse(silkExtension.isCorrectGameJarByContent(wrongJar), "Should not identify JAR with wrong classes");
        assertFalse(silkExtension.isCorrectGameJarByContent(notAJar), "Should not identify a non-JAR file");
        assertFalse(silkExtension.isCorrectGameJarByContent(emptyJar), "Should not identify an empty JAR as correct");
    }

    @Test
    @DisplayName("findEquilinoxGameJar finds JAR by name in simulated Windows Steam dir")
    void findEquilinoxGameJar_findsByName_Windows() throws Exception {
        Path fakeSteamRoot = projectDir.resolve("MyWindowsSteamLib");
        Path gameDir = fakeSteamRoot.resolve("steamapps/common/Equilinox");
        Files.createDirectories(gameDir);
        createTemporaryGameJar(
                gameDir, "EquilinoxWindows.jar", List.of("main/MainApp.class", "main/FirstScreenUi.class"));

        SystemLambda.restoreSystemProperties(() -> {
            System.setProperty("os.name", "windows");

            SilkExtension localSilkExtension = new SilkExtension(project.getObjects(), project) {
                @Override
                protected List<Path> findSteamLibraryRoots(String os) {
                    assertEquals("windows", os.toLowerCase());
                    return Collections.singletonList(fakeSteamRoot);
                }
            };

            FileCollection gameJarFc = localSilkExtension.findEquilinoxGameJar();
            assertNotNull(gameJarFc);
            assertFalse(gameJarFc.isEmpty());
            assertEquals("EquilinoxWindows.jar", gameJarFc.getSingleFile().getName());
        });
    }

    @Test
    @DisplayName("findEquilinoxGameJar finds JAR by content when name fails")
    void findEquilinoxGameJar_findByContent() throws Exception {
        Path fakeSteamRoot = projectDir.resolve("MyFakeSteamLib");
        Path gameDir = fakeSteamRoot.resolve("steamapps/common/Equilinox");
        Files.createDirectories(gameDir);
        createTemporaryGameJar(gameDir, "SomeGame.jar", List.of("main/MainApp.class", "main/FirstScreenUi.class"));

        SystemLambda.restoreSystemProperties(() -> {
            System.setProperty("os.name", "linux");

            SilkExtension localSilkExtension = new SilkExtension(project.getObjects(), project) {
                @Override
                protected List<Path> findSteamLibraryRoots(String os) {
                    assertEquals("linux", os.toLowerCase());
                    return Collections.singletonList(fakeSteamRoot);
                }
            };

            FileCollection gameJarFc = localSilkExtension.findEquilinoxGameJar();
            assertNotNull(gameJarFc);
            assertFalse(gameJarFc.isEmpty());
            assertEquals("SomeGame.jar", gameJarFc.getSingleFile().getName());
        });
    }

    @Test
    @DisplayName("findEquilinoxGameJar throws GradleException when no JAR is found")
    void findEquilinoxGameJar_throwsWhenNotFound() throws Exception {
        Path fakeSteamRoot = projectDir.resolve("EmptySteamLib");
        Files.createDirectories(fakeSteamRoot.resolve("steamapps/common/Equilinox"));

        SystemLambda.restoreSystemProperties(() -> {
            System.setProperty("os.name", "mac os x");

            SilkExtension localSilkExtension = new SilkExtension(project.getObjects(), project) {
                @Override
                protected List<Path> findSteamLibraryRoots(String os) {
                    assertEquals("mac os x", os.toLowerCase());
                    return Collections.singletonList(fakeSteamRoot);
                }
            };

            Exception exception = assertThrows(GradleException.class, localSilkExtension::findEquilinoxGameJar);
            assertTrue(exception.getMessage().contains("Could not automatically find Equilinox game JAR"));
        });
    }

    @Test
    @DisplayName("findEquilinoxGameJar uses cache on second call if file exists")
    void findEquilinoxGameJar_usesCache_ifFileExists() throws Exception {
        Path fakeSteamRoot = projectDir.resolve("CacheSteamLib");
        Path gameDir = fakeSteamRoot.resolve("steamapps/common/Equilinox");
        Files.createDirectories(gameDir);
        File gameJarFile = createTemporaryGameJar(
                gameDir, "Equilinox.jar", List.of("main/MainApp.class", "main/FirstScreenUi.class"));

        SystemLambda.restoreSystemProperties(() -> {
            System.setProperty("os.name", "linux");

            SilkExtension cachingSilkExtension = new SilkExtension(project.getObjects(), project) {
                private boolean findRootsCalled = false;

                @Override
                protected List<Path> findSteamLibraryRoots(String os) {
                    assertFalse(
                            findRootsCalled,
                            "findSteamLibraryRoots should only be effectively called once for a successful find due to caching of FileCollection");
                    findRootsCalled = true;
                    return Collections.singletonList(fakeSteamRoot);
                }
            };

            FileCollection gameJarFc1 = cachingSilkExtension.findEquilinoxGameJar();
            assertNotNull(gameJarFc1);
            assertEquals("Equilinox.jar", gameJarFc1.getSingleFile().getName());

            FileCollection gameJarFc2 = cachingSilkExtension.findEquilinoxGameJar();
            assertSame(
                    gameJarFc1,
                    gameJarFc2,
                    "Expected cached FileCollection instance to be returned if file still exists.");
        });
    }

    @Test
    @DisplayName("findEquilinoxGameJar re-scans if cached file is deleted")
    void findEquilinoxGameJar_rescans_ifCachedFileDeleted() throws Exception {
        Path fakeSteamRoot = projectDir.resolve("CacheInvalidationSteamLib");
        Path gameDir = fakeSteamRoot.resolve("steamapps/common/Equilinox");
        Files.createDirectories(gameDir);
        File gameJarFile = createTemporaryGameJar(
                gameDir, "EquilinoxToDelete.jar", List.of("main/MainApp.class", "main/FirstScreenUi.class"));

        SystemLambda.restoreSystemProperties(() -> {
            System.setProperty("os.name", "linux");

            AtomicInteger findRootsCallCount = new AtomicInteger(0);
            SilkExtension localSilkExtension = new SilkExtension(project.getObjects(), project) {
                @Override
                protected List<Path> findSteamLibraryRoots(String os) {
                    findRootsCallCount.getAndIncrement();
                    return Collections.singletonList(fakeSteamRoot);
                }
            };

            FileCollection gameJarFc1 = localSilkExtension.findEquilinoxGameJar();
            assertNotNull(gameJarFc1);
            assertEquals("EquilinoxToDelete.jar", gameJarFc1.getSingleFile().getName());
            assertEquals(1, findRootsCallCount.get(), "findSteamLibraryRoots should be called once for initial find.");

            assertTrue(gameJarFile.delete(), "Failed to delete the game JAR for cache test.");

            Exception exception = assertThrows(GradleException.class, localSilkExtension::findEquilinoxGameJar);
            assertTrue(exception.getMessage().contains("Could not automatically find Equilinox game JAR"));
            assertTrue(
                    findRootsCallCount.get() > 1,
                    "findSteamLibraryRoots should be called again after cached file deletion.");
        });
    }

    @Test
    @DisplayName("findEquilinoxGameJar re-scans if the first attempt failed (no JAR found)")
    void findEquilinoxGameJar_rescansIfPreviousAttemptFailed() throws Exception {
        Path fakeSteamRoot = projectDir.resolve("InitiallyEmptySteamLib");
        Path gameDir = fakeSteamRoot.resolve("steamapps/common/Equilinox");
        Files.createDirectories(gameDir);

        AtomicInteger findRootsCallCount = new AtomicInteger(0);
        SilkExtension localSilkExtension = new SilkExtension(project.getObjects(), project) {
            @Override
            protected List<Path> findSteamLibraryRoots(String os) {
                findRootsCallCount.incrementAndGet();
                return Collections.singletonList(fakeSteamRoot);
            }
        };

        SystemLambda.restoreSystemProperties(() -> {
            System.setProperty("os.name", "linux");

            assertThrows(
                    GradleException.class,
                    localSilkExtension::findEquilinoxGameJar,
                    "Should throw when game JAR is not found.");
            assertEquals(
                    1, findRootsCallCount.get(), "findSteamLibraryRoots should be called once for the first attempt.");
            assertNull(localSilkExtension.cachedEquilinoxGameJarFc, "Cache should be null after a failed attempt.");

            File gameJarFile = createTemporaryGameJar(gameDir, "Equilinox.jar", SilkExtension.EQUILINOX_CLASS_FILES);
            assertTrue(gameJarFile.exists());

            FileCollection foundFc;
            try {
                foundFc = localSilkExtension.findEquilinoxGameJar();
            } finally {
                if (gameJarFile.exists()) gameJarFile.delete();
            }

            assertNotNull(foundFc);
            assertFalse(foundFc.isEmpty());
            assertEquals("Equilinox.jar", foundFc.getSingleFile().getName());
            assertTrue(
                    findRootsCallCount.get() > 1,
                    "findSteamLibraryRoots should be called again for the second attempt.");
        });
    }

    @Test
    @DisplayName("initializeGameJarProvider picks first file from multi-file configuration")
    void initializeGameJarProvider_multiFile() throws IOException {
        File fakeGameJarFile1 = projectDir.resolve("EquilinoxGame1.jar").toFile();
        assertTrue(fakeGameJarFile1.createNewFile());
        File fakeGameJarFile2 = projectDir.resolve("EquilinoxGame2.jar").toFile();
        assertTrue(fakeGameJarFile2.createNewFile());

        Configuration mockEquilinoxConfig = mock(Configuration.class);
        when(mockEquilinoxConfig.getFiles())
                .thenReturn(new LinkedHashSet<>(List.of(fakeGameJarFile1, fakeGameJarFile2)));
        when(mockEquilinoxConfig.getName()).thenReturn("equilinoxTestMultiConfig");

        silkExtension.initializeGameJarProvider(mockEquilinoxConfig, project);
        Provider<RegularFile> gameJarProvider = silkExtension.getGameJar();

        assertNotNull(gameJarProvider);
        assertTrue(gameJarProvider.isPresent());
        assertEquals(
                fakeGameJarFile1.getName(), gameJarProvider.get().getAsFile().getName());
    }

    @Test
    @DisplayName("getRegisteredSubprojectsInternal returns an unmodifiable list")
    void getRegisteredSubprojectsInternal_isUnmodifiable() {
        Project subProjectA =
                ProjectBuilder.builder().withName("subA").withParent(project).build();
        silkExtension.getMods().register(subProjectA);

        List<Project> registered = silkExtension.getRegisteredSubprojectsInternal();
        assertThrows(
                UnsupportedOperationException.class,
                () -> registered.add(ProjectBuilder.builder()
                        .withName("subB")
                        .withParent(project)
                        .build()));
    }

    @Test
    @DisplayName("isCorrectGameJarByContent handles IOException when opening JAR")
    void isCorrectGameJarByContent_handlesIoExceptionOnOpen() throws IOException {
        Path problematicJar = projectDir.resolve("problem.jar");
        Files.writeString(problematicJar, "not a jar");

        try (MockedStatic<FileSystems> mockedFileSystems = mockStatic(FileSystems.class)) {
            mockedFileSystems
                    .when(() -> FileSystems.newFileSystem(eq(problematicJar), anyMap()))
                    .thenThrow(new IOException("Test_IO_Exception"));

            assertFalse(silkExtension.isCorrectGameJarByContent(problematicJar));
        }
    }

    @ParameterizedTest(name = "[{index}] os={0}, userHome={1}")
    @MethodSource("steamRootsScenarios")
    @DisplayName("findSteamLibraryRoots correctly identifies roots for various scenarios")
    void testFindSteamLibraryRoots_Parameterized(
            String osName,
            String userHomeString,
            List<String> pathsToCreateInJimfs,
            String vdfFileToCreateFullPath,
            String vdfContent,
            List<String> expectedPathStrings)
            throws Exception {

        com.google.common.jimfs.Configuration jimfsConfig;
        if (osName.toLowerCase().startsWith("win")) {
            Set<String> drives = new HashSet<>();
            pathsToCreateInJimfs.stream()
                    .filter(s -> s.matches("^[a-zA-Z]:\\\\.*"))
                    .map(s -> s.substring(0, 2) + "\\")
                    .forEach(drives::add);
            if (vdfFileToCreateFullPath != null && vdfFileToCreateFullPath.matches("^[a-zA-Z]:\\\\.*")) {
                drives.add(vdfFileToCreateFullPath.substring(0, 2) + "\\");
            }
            if (userHomeString.matches("^[a-zA-Z]:\\\\.*")) {
                drives.add(userHomeString.substring(0, 2) + "\\");
            }
            drives.add("C:\\");

            String removedDrive = null;
            Iterator<String> iterator = drives.iterator();
            if (iterator.hasNext()) {
                removedDrive = iterator.next();
                iterator.remove();
            }
            assertNotNull(removedDrive, "At least one drive is required.");

            String[] remainingDrives = drives.toArray(new String[0]);

            jimfsConfig = com.google.common.jimfs.Configuration.windows().toBuilder()
                    .setRoots(removedDrive, remainingDrives)
                    .setWorkingDirectory("C:\\")
                    .setAttributeViews("basic", "owner", "dos", "user")
                    .build();
        } else {
            jimfsConfig = com.google.common.jimfs.Configuration.unix().toBuilder()
                    .setWorkingDirectory("/")
                    .setAttributeViews("basic", "owner", "posix", "user")
                    .build();
        }

        try (FileSystem jimfs = Jimfs.newFileSystem(jimfsConfig)) {
            for (String pathStr : pathsToCreateInJimfs) {
                Path p = jimfs.getPath(pathStr);
                Files.createDirectories(p);
            }

            Path jimfsUserHome = jimfs.getPath(userHomeString);
            Files.createDirectories(jimfsUserHome);

            if (vdfFileToCreateFullPath != null && vdfContent != null) {
                Path vdfPathInJimfs = jimfs.getPath(vdfFileToCreateFullPath);
                Files.createDirectories(vdfPathInJimfs.getParent());
                Files.writeString(vdfPathInJimfs, vdfContent);
            }

            SystemLambda.restoreSystemProperties(() -> {
                System.setProperty("os.name", osName);
                System.setProperty("user.home", jimfsUserHome.toString());

                try (MockedStatic<Paths> mockedPaths = mockStatic(Paths.class);
                        MockedStatic<Files> ignored = mockStatic(Files.class, CALLS_REAL_METHODS)) {

                    mockedPaths
                            .when(() -> Paths.get(anyString(), any(String[].class)))
                            .thenAnswer(invocation -> {
                                Object[] allActualArguments = invocation.getArguments();
                                String first = (String) allActualArguments[0];
                                String[] moreArray;

                                if (allActualArguments.length > 1) {
                                    moreArray = new String[allActualArguments.length - 1];
                                    for (int i = 0; i < moreArray.length; i++) {
                                        if (allActualArguments[i + 1] instanceof String) {
                                            moreArray[i] = (String) allActualArguments[i + 1];
                                        } else {
                                            throw new IllegalStateException("Vararg element " + i + " is not a String: "
                                                    + allActualArguments[i + 1]);
                                        }
                                    }
                                } else {
                                    moreArray = new String[0];
                                }

                                return jimfs.getPath(first, moreArray);
                            });

                    mockedPaths.when(() -> Paths.get(any(URI.class))).thenAnswer(invocation -> jimfs.provider()
                            .getPath(invocation.getArgument(0)));

                    Project testProject = ProjectBuilder.builder()
                            .withProjectDir(projectDir.toFile())
                            .build();
                    SilkExtension localSilkExtension =
                            testProject.getExtensions().create("silk", SilkExtension.class);

                    List<Path> actualRoots = localSilkExtension.findSteamLibraryRoots(osName);
                    Set<Path> expectedRootsSet =
                            expectedPathStrings.stream().map(jimfs::getPath).collect(Collectors.toSet());
                    Set<Path> actualRootsSet = new HashSet<>(actualRoots);

                    assertEquals(
                            expectedRootsSet.size(),
                            actualRootsSet.size(),
                            () -> "Number of roots mismatch for OS: " + osName + ". Expected: " + expectedRootsSet
                                    + ", Actual: " + actualRootsSet);
                    assertEquals(
                            expectedRootsSet,
                            actualRootsSet,
                            () -> "Set of roots mismatch for OS: " + osName + ". Expected: " + expectedRootsSet
                                    + ", Actual: " + actualRootsSet);
                }
            });
        }
    }

    static Stream<Arguments> steamRootsScenarios() {
        return Stream.of(
                Arguments.of(
                        "windows",
                        "C:\\Users\\TestUser",
                        List.of("C:\\Program Files (x86)\\Steam"),
                        null,
                        null,
                        List.of("C:\\Program Files (x86)\\Steam")),
                Arguments.of(
                        "windows",
                        "C:\\Users\\TestUser",
                        List.of("C:\\Program Files (x86)\\Steam", "D:\\MySteamLib"),
                        "C:\\Program Files (x86)\\Steam\\steamapps\\libraryfolders.vdf",
                        "\"libraryfolders\"\n{\n  \"0\"\n  {\n    \"path\"		\"C:\\\\Program Files (x86)\\\\Steam\"\n  }\n  \"1\"\n  {\n    \"path\"		\"D:\\\\MySteamLib\"\n  }\n}",
                        List.of("C:\\Program Files (x86)\\Steam", "D:\\MySteamLib")),
                Arguments.of(
                        "linux",
                        "/home/testuser",
                        List.of(
                                "/home/testuser/.steam/steam",
                                "/mnt/gamedrive/SteamLibrary",
                                "/home/testuser/.local/share/Steam",
                                "/home/testuser/.var/app/com.valvesoftware.Steam/.steam/steam"),
                        "/home/testuser/.steam/steam/steamapps/libraryfolders.vdf",
                        "\"libraryfolders\"\n{\n  \"0\"\n  {\n    \"path\"     \"/home/testuser/.steam/steam\"\n  }\n  \"1\"\n  {\n    \"path\"      \"/mnt/gamedrive/SteamLibrary\"\n  }\n}",
                        List.of(
                                "/home/testuser/.steam/steam",
                                "/mnt/gamedrive/SteamLibrary",
                                "/home/testuser/.local/share/Steam",
                                "/home/testuser/.var/app/com.valvesoftware.Steam/.steam/steam")),
                Arguments.of(
                        "mac os x",
                        "/Users/testuser",
                        List.of("/Users/testuser/Library/Application Support/Steam"),
                        null,
                        null,
                        List.of("/Users/testuser/Library/Application Support/Steam")),
                Arguments.of(
                        "windows",
                        "C:\\Users\\TestUser",
                        List.of("C:\\Users\\TestUser\\scoop\\apps\\steam\\current"),
                        null,
                        null,
                        List.of("C:\\Users\\TestUser\\scoop\\apps\\steam\\current")),
                Arguments.of(
                        "windows",
                        "C:\\Users\\TestUser",
                        List.of("C:\\Program Files (x86)\\Steam"),
                        "C:\\Program Files (x86)\\Steam\\steamapps\\libraryfolders.vdf",
                        "\"libraryfolders\"\n{\n  \"0\"\n  {\n    \"path\"		\"E:\\\\NonExistentSteamLib\"\n  }\n \"1\"\n {\n \"path\" \"C:\\\\Program Files (x86)\\\\Steam\"\n }\n}",
                        List.of("C:\\Program Files (x86)\\Steam")),
                Arguments.of(
                        "windows",
                        "C:\\Users\\TestUser",
                        List.of("C:\\Program Files (x86)\\Steam"),
                        "C:\\Program Files (x86)\\Steam\\steamapps\\libraryfolders.vdf",
                        "\"libraryfolders\"\n{\n  \"not_a_path\"		\"C:\\\\SomeOtherDir\"\n}",
                        List.of("C:\\Program Files (x86)\\Steam")),
                Arguments.of(
                        "linux",
                        "/home/testuser",
                        List.of("/home/testuser/.steam/steam"),
                        "/home/testuser/.steam/steam/steamapps/libraryfolders.vdf",
                        "",
                        List.of("/home/testuser/.steam/steam")),
                Arguments.of(
                        "linux",
                        "/home/testuser",
                        List.of("/home/testuser/.local/share/Steam"),
                        null, // No VDF
                        null,
                        List.of("/home/testuser/.local/share/Steam")),
                Arguments.of(
                        "linux",
                        "/home/testuser",
                        List.of("/home/testuser/.var/app/com.valvesoftware.Steam/.steam/steam"),
                        null,
                        null,
                        List.of("/home/testuser/.var/app/com.valvesoftware.Steam/.steam/steam")));
    }
}
