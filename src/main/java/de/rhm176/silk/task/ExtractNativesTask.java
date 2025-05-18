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

import java.io.*;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

/**
 * A Gradle task that extracts native library files (e.g., .dll, .so, .jnilib)
 * from a specified input JAR file into a designated output directory.
 */
public abstract class ExtractNativesTask extends DefaultTask {
    /**
     * Specifies the input JAR file from which native libraries will be extracted.
     * This property must be set for the task to execute correctly.
     *
     * @return A {@link RegularFileProperty} representing the input JAR file.
     */
    @InputFile
    public abstract RegularFileProperty getInputJar();

    /**
     * Specifies the directory where the extracted native library files will be placed.
     * If the directory exists, its contents will be cleared before extraction.
     * If it does not exist, it will be created.
     * This property must be set for the task to execute correctly.
     *
     * @return A {@link DirectoryProperty} representing the output directory for natives.
     */
    @OutputDirectory
    public abstract DirectoryProperty getNativesDir();

    /**
     * Provides the name of the current operating system.
     * This is used as an input to ensure the task re-runs if the OS changes
     * between builds (affecting cache validity) and to determine the correct
     * native file extensions (e.g., .dll for Windows, .so for Linux).
     * Defaults to {@code System.getProperty("os.name")}.
     *
     * @return A {@link Property} containing the OS name string.
     */
    @Input // if the os ever changes and the cache isn't deleted, this will make it re-run
    public abstract Property<String> getOsName();

    /**
     * Constructs a new ExtractNativesTask.
     * Initializes the {@code osName} property with the value of the
     * {@code os.name} system property.
     */
    public ExtractNativesTask() {
        getOsName().convention(System.getProperty("os.name"));
    }

    /**
     * Executes the native extraction process.
     * <p>
     * The process involves:
     * <ol>
     * <li>Validating the existence of the input JAR.</li>
     * <li>Preparing the output directory by clearing it if it exists or creating it if it doesn't.</li>
     * <li>Opening the input JAR file.</li>
     * <li>Iterating over JAR entries to find files that match native library patterns
     * (based on file extension and OS, determined by {@link #isNativeFile}).</li>
     * <li>Copying qualifying native files from the JAR to the root of the output directory.</li>
     * </ol>
     * Logs information about the process, including the number of files extracted.
     *
     * @throws GradleException if the input JAR does not exist, if the output directory
     * cannot be prepared, or if an I/O error occurs during
     * JAR processing or file extraction.
     */
    @TaskAction
    public void execute() {
        File jar = getInputJar().get().getAsFile();
        File nativesDir = getNativesDir().get().getAsFile();

        if (!jar.exists()) {
            throw new GradleException("Input JAR does not exist: " + jar.getAbsolutePath());
        }

        if (nativesDir.exists()) {
            if (!nativesDir.isDirectory()) {
                throw new GradleException(
                        "Natives output directory is a file, not a directory: " + nativesDir.getAbsolutePath());
            }
            try {
                Files.walk(nativesDir.toPath())
                        .filter(p -> !p.equals(nativesDir.toPath()))
                        .map(java.nio.file.Path::toFile)
                        .sorted((o1, o2) -> -o1.compareTo(o2))
                        .forEach(File::delete);
            } catch (IOException e) {
                throw new GradleException("Failed to clear natives directory: " + nativesDir.getAbsolutePath(), e);
            }
        } else {
            if (!nativesDir.mkdirs()) {
                throw new GradleException("Could not create natives directory: " + nativesDir.getAbsolutePath());
            }
        }
        getLogger().info("Extracting natives from {} to {}", jar.getName(), nativesDir.getAbsolutePath());

        try (JarFile jarFile = new JarFile(jar, false)) {
            Enumeration<JarEntry> entries = jarFile.entries();
            int extractedCount = 0;

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory()
                        && !entry.getName().contains("/")
                        && !entry.getName().contains("\\")
                        && isNativeFile(entry.getName(), getOsName().get())) {

                    File outputFile = new File(nativesDir, entry.getName());
                    try (InputStream in = jarFile.getInputStream(entry);
                            OutputStream out = new FileOutputStream(outputFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        extractedCount++;
                        getLogger().debug("Extracted native file: {}", entry.getName());
                    } catch (IOException e) {
                        throw new GradleException("Failed to extract native file: " + entry.getName(), e);
                    }
                }
            }
            getLogger().info("Extracted {} native files.", extractedCount);
        } catch (IOException e) {
            throw new GradleException("Error processing JAR file: " + jar.getAbsolutePath(), e);
        }
    }

    private static boolean isNativeFile(String entryName, String osName) {
        String name = entryName.toLowerCase();
        if (osName.startsWith("Win")) {
            return name.endsWith(".dll");
        } else if (osName.startsWith("Linux")) {
            return name.endsWith(".so");
        } else if (osName.startsWith("Mac") || osName.startsWith("Darwin")) {
            return name.endsWith(".jnilib") || name.endsWith(".dylib");
        }
        return false;
    }
}
