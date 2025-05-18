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

import org.gradle.api.provider.Property;

/**
 * Represents a single entrypoint declaration within an entrypoint list in {@code fabric.mod.json}.
 * <p>
 * An entrypoint declaration specifies a class, static field, or static method to be invoked
 * by the Fabric Loader. It can be a simple string value (implying the "default" language adapter)
 * or an object specifying both a "value" and an "adapter".
 * <p>
 * Example of how this is serialized in {@code fabric.mod.json}:
 * <ul>
 * <li>Simple form: {@code "com.example.MyModMain"}</li>
 * <li>Object form: {@code {"adapter": "customAdapter", "value": "com.example.MyModMain"}}</li>
 * </ul>
 *
 * @see EntrypointContainerExtension
 * @see <a href="https://fabricmc.net/wiki/documentation:fabric_mod_json">fabric.mod.json specification</a>
 */
public abstract class EntrypointExtension {
    /**
     * The value of the entrypoint. This is a mandatory string.
     * <p>
     * It can be a fully qualified class name (e.g., {@code "my.package.MyClass"}) which points
     * to a class to be instantiated, or a class name followed by {@code ::} and a field or
     * method name (e.g., {@code "my.package.MyClass::thing"}).
     *
     * @return A {@link Property} for the entrypoint value string.
     */
    public abstract Property<String> getValue();
    /**
     * The language adapter to use for this entrypoint. This is an optional string.
     * <p>
     * If this property is not set, is empty, or is set to {@code "default"}, the Fabric Loader
     * assumes the default language adapter.
     *
     * @return A {@link Property} for the language adapter string.
     */
    public abstract Property<String> getAdapter();
}
