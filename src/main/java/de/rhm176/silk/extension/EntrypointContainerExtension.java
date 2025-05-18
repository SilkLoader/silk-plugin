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
package de.rhm176.silk.extension;

import java.util.Collections;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;

/**
 * Represents the top-level {@code entrypoints} object in a {@code fabric.mod.json} file.
 * <p>
 * This container holds mappings from entrypoint types (e.g., "main")
 * to lists of entrypoint declarations. Each declaration specifies a class or field/method
 * to be initialized by the Fabric Loader for that entrypoint type.
 * <p>
 * It is typically configured within the {@link FabricExtension} using a nested DSL:
 * <pre>
 * fabric {
 *     entrypoints {
 *         type("main") {
 *             entry("com.example.MyModMain")
 *             entry {
 *                 value.set("com.example.AnotherMain")
 *                 adapter.set("custom")
 *             }
 *         }
 *     }
 * }
 * </pre>
 * @see FabricExtension#getEntrypoints()
 * @see EntrypointExtension
 * @see <a href="https://fabricmc.net/wiki/documentation:fabric_mod_json">fabric.mod.json specification</a>
 */
public abstract class EntrypointContainerExtension {
    private final ObjectFactory objectFactory;

    /**
     * Gets the map defining entrypoints for different types.
     * <p>
     * The keys of this map are strings representing the entrypoint type (e.g., "main",).
     * The values are {@link ListProperty} instances, each containing {@link EntrypointExtension} objects
     * that declare the actual entrypoints for that type.
     *
     * @return A {@link MapProperty} where keys are entrypoint type strings and values are lists of entrypoint declarations.
     */
    @Input
    @Optional
    public abstract MapProperty<String, ListProperty<EntrypointExtension>> getTypes();

    /**
     * Constructs an EntrypointContainerExtension.
     * This constructor is typically called by Gradle's {@link ObjectFactory}.
     *
     * @param objectFactory The Gradle {@link ObjectFactory} for creating managed objects if needed for DSL helpers.
     */
    @Inject
    public EntrypointContainerExtension(ObjectFactory objectFactory) {
        this.objectFactory = objectFactory;

        getTypes().convention(Collections.emptyMap());
    }

    /**
     * Configures the list of entrypoint declarations for a specific entrypoint type
     * (e.g., "main") using a nested builder DSL.
     * <p>
     * If the specified {@code entrypointType} does not already exist in the map,
     * a new list will be created for it.
     *
     * @param entrypointType The name of the entrypoint type (e.g., "main"). Cannot be null or empty.
     * @param configureAction The configuration action that receives an {@link EntrypointListBuilderExtension}.
     * This builder is then used to add individual entrypoint declarations
     * for the specified {@code entrypointType}.
     * @throws InvalidUserDataException if {@code entrypointType} is null or empty.
     */
    public void type(String entrypointType, Action<? super EntrypointListBuilderExtension> configureAction) {
        if (entrypointType == null || entrypointType.trim().isEmpty()) {
            throw new InvalidUserDataException("Entrypoint type cannot be null or empty.");
        }
        ListProperty<EntrypointExtension> listProperty = getTypes().get().get(entrypointType);
        if (listProperty == null) {
            listProperty = objectFactory.listProperty(EntrypointExtension.class);
            getTypes().put(entrypointType, listProperty);
        }

        EntrypointListBuilderExtension builder =
                objectFactory.newInstance(EntrypointListBuilderExtension.class, objectFactory, listProperty);
        configureAction.execute(builder);
    }
}
