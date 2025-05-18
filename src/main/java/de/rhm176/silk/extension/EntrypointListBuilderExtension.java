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

import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;

/**
 * A DSL helper class for configuring a list of entrypoint declarations for a specific
 * entrypoint type (e.g., "main") within the {@code fabric.mod.json}.
 * <p>
 * This builder is typically accessed via the {@link EntrypointContainerExtension#type(String, Action)}
 * method, allowing a nested configuration like:
 * <pre>
 * entrypoints {
 *     type("main") {
 *         entry("com.example.MyMainClass")
 *         entry { anEntrypoint ->
 *             anEntrypoint.getValue().set("com.example.AnotherMain")
 *             anEntrypoint.getAdapter().set("custom")
 *         }
 *     }
 * }
 * </pre>
 * Each call to {@code entry(...)} on this builder adds an {@link EntrypointExtension}
 * to the target list associated with a specific entrypoint type.
 *
 * @see EntrypointContainerExtension
 * @see EntrypointExtension
 */
public abstract class EntrypointListBuilderExtension {
    private final ObjectFactory objectFactory;
    private final ListProperty<EntrypointExtension> targetList;

    /**
     * Constructs an {@code EntrypointListBuilderExtension}.
     * This constructor is typically called by Gradle's {@link ObjectFactory} when an instance
     * is created within the {@link EntrypointContainerExtension#type(String, Action)} method.
     *
     * @param objectFactory The Gradle {@link ObjectFactory} used to instantiate {@link EntrypointExtension} objects.
     * @param targetList    The {@link ListProperty} from {@link EntrypointContainerExtension#getTypes()}
     * that this builder will populate for a specific entrypoint type.
     */
    @Inject
    public EntrypointListBuilderExtension(ObjectFactory objectFactory, ListProperty<EntrypointExtension> targetList) {
        this.objectFactory = objectFactory;
        this.targetList = targetList;
    }

    /**
     * Adds a simple entrypoint declaration with only a value string.
     * The language adapter is assumed to be "default".
     *
     * @param value The entrypoint value string (e.g., "com.example.MyClass" or "com.example.MyClass::myField").
     * Cannot be null or empty.
     * @throws InvalidUserDataException if the provided value is null or empty.
     */
    public void entry(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new InvalidUserDataException("Entrypoint value cannot be null or empty.");
        }
        EntrypointExtension entryConfig = objectFactory.newInstance(EntrypointExtension.class);
        entryConfig.getValue().set(value);
        targetList.add(entryConfig);
    }

    /**
     * Adds an entrypoint declaration allowing full configuration of its value and adapter
     * via an {@link Action}.
     * <p>
     * Example usage:
     * <pre>
     * entry { entrypoint ->
     *     entrypoint.value.set("com.example.MyAdapterEntry")
     *     entrypoint.adapter.set("custom_adapter")
     * }
     * </pre>
     *
     * @param configureAction The action to configure the {@link EntrypointExtension}.
     * The {@link EntrypointExtension#getValue()} property must be set to a non-empty string
     * within this action.
     * @throws InvalidUserDataException if the {@code value} property of the {@link EntrypointExtension}
     * is not set or is empty after configuration.
     */
    public void entry(Action<? super EntrypointExtension> configureAction) {
        EntrypointExtension entryConfig = objectFactory.newInstance(EntrypointExtension.class);
        configureAction.execute(entryConfig);

        if (!entryConfig.getValue().isPresent()
                || entryConfig.getValue().get().trim().isEmpty()) {
            throw new InvalidUserDataException(
                    "Entrypoint 'value' is mandatory when configuring an entrypoint object.");
        }
        targetList.add(entryConfig);
    }
}
