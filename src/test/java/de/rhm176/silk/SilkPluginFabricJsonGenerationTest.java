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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;

public class SilkPluginFabricJsonGenerationTest extends BaseSilkPluginTest {
    private void assertTaskOutcome(TaskOutcome outcome, BuildTask task) {
        assertNotNull(task);
        assertEquals(outcome, task.getOutcome());
    }

    @Test
    void generateFabricModJsonCreatesFileWhenEnabled() throws IOException {
        configurePluginWithGameJar(dummyEquilinoxJar.getName(), null, null, true);

        BuildResult result =
                createRunner(SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME).build();

        assertTaskOutcome(TaskOutcome.SUCCESS, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));

        File generatedFile = new File(projectDir, "build/generated/silk/resources/main/fabric.mod.json");
        assertTrue(generatedFile.exists());
        String content = Files.readString(generatedFile.toPath()).replace(" ", "");
        assertTrue(content.contains("\"id\":\"mytestmod\""));
        assertTrue(content.contains("\"version\":\"1.0.0\""));
    }

    @Test
    void generateFabricModJsonSkippedWhenDisabled() throws IOException {
        configurePluginWithGameJar(dummyEquilinoxJar.getName(), null, null, false);

        BuildResult result =
                createRunner(SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME).build();

        assertTaskOutcome(TaskOutcome.SKIPPED, result.task(":" + SilkPlugin.GENERATE_FABRIC_MOD_JSON_TASK_NAME));
        File generatedFile = new File(projectDir, "build/generated/silk/resources/main/fabric.mod.json");
        assertFalse(generatedFile.exists());
    }
}
