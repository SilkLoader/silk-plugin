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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;

public class SilkPluginSilkLoaderConfigTest extends BaseSilkPluginTest {
    @Test
    void effectiveLoaderMainClassUsesOverride() throws IOException {
        createDummyJar(dummyLoaderJar.toPath(), "com.example.ActualMainInManifest");
        configurePluginWithGameJar(
                dummyEquilinoxJar.getName(), dummyLoaderJar.getName(), "com.example.OverriddenMain", true);

        appendBuildGradle(
                "afterEvaluate {",
                "  tasks.named('" + SilkPlugin.DEFAULT_RUN_GAME_TASK_NAME + "', JavaExec) {",
                "    println \"runGameMainClass: ${it.mainClass.getOrNull()}\"",
                "  }",
                "}");
        BuildResult result =
                createRunner(SilkPlugin.DEFAULT_RUN_GAME_TASK_NAME, "--dry-run").build();
        assertTrue(result.getOutput().contains("runGameMainClass: com.example.OverriddenMain"));
    }

    @Test
    void effectiveLoaderMainClassUsesManifestIfNotOverridden() throws IOException {
        createDummyJar(dummyLoaderJar.toPath(), "com.example.ActualMainInManifest");
        configurePluginWithGameJar(dummyEquilinoxJar.getName(), dummyLoaderJar.getName(), null, true);

        appendBuildGradle(
                "afterEvaluate {",
                "  tasks.named('" + SilkPlugin.DEFAULT_RUN_GAME_TASK_NAME + "', JavaExec) {",
                "    println \"runGameMainClass: ${it.mainClass.getOrNull()}\"",
                "  }",
                "}");
        BuildResult result =
                createRunner(SilkPlugin.DEFAULT_RUN_GAME_TASK_NAME, "--dry-run").build();
        assertTrue(result.getOutput().contains("runGameMainClass: com.example.ActualMainInManifest"));
    }
}
