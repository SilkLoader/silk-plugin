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

import java.io.File;
import java.io.IOException;
import org.gradle.testkit.runner.BuildResult;
import org.junit.jupiter.api.Test;

public class SilkPluginApplyTest extends BaseSilkPluginTest {
    @Test
    void pluginAppliesSuccessfullyAndCreatesTasks() throws IOException {
        configurePluginWithGameJar(dummyEquilinoxJar.getName(), null, null, false);

        BuildResult result = createRunner("tasks", "--all").build();

        assertTrue(
                result.getOutput().contains("Silk tasks"),
                "Silk task group should be present if tasks are added to it");
        assertTrue(result.getOutput().contains(SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(result.getOutput().contains(SilkPlugin.MODIFY_FABRIC_MOD_JSON_TASK_NAME));
        assertTrue(result.getOutput().contains(SilkPlugin.TRANSFORM_CLASSES_TASK_NAME));
        assertTrue(result.getOutput().contains(SilkPlugin.EXTRACT_NATIVES_TASK_NAME));
        assertTrue(result.getOutput().contains(SilkPlugin.GENERATE_SOURCES_TASK_NAME));
        assertTrue(result.getOutput().contains(SilkPlugin.RUN_GAME_TASK_NAME));
    }

    @Test
    void afterEvaluate_throwsExceptionIfGenerateIsTrueAndManualFmjExists() throws IOException {
        File manualFmj = new File(mainResourcesDir, "fabric.mod.json");
        writeFile(manualFmj, "{ \"schemaVersion\": 1, \"id\": \"manual\", \"version\": \"1.0.0\" }");

        configurePluginWithGameJar(dummyEquilinoxJar.getName(), null, null, true);

        BuildResult result = createRunner("tasks").buildAndFail();

        assertTrue(result.getOutput()
                .contains("Silk Plugin: 'silk.generateFabricModJson' is true, but '" + manualFmj.getAbsolutePath()
                        + "' also exists."));
    }
}
