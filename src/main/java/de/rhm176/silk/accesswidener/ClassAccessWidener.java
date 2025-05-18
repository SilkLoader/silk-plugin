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
package de.rhm176.silk.accesswidener;

import java.util.List;

/**
 * Represents an Access Widener rule specifically targeting a class.
 * <p>
 * This rule defines how the access or {@code final} status of a specific class
 * should be modified. It stores the target class name and the {@link AccessModifier}
 * to be applied (e.g., {@code accessible} or {@code extendable}).
 * <p>
 * An example line in an {@code .accesswidener} file for a class rule:
 * <pre>{@code accessible class com/example/MyTargetClass}</pre>
 * or
 * <pre>{@code extendable class com/example/another/AnotherClass}</pre>
 *
 * @see AccessWidenerRule
 * @see AccessModifier
 * @see de.rhm176.silk.task.TransformClassesTask
 */
public final class ClassAccessWidener extends AccessWidenerRule {
    /**
     * Constructs a new rule for widening access to a class.
     *
     * @param modifier The {@link AccessModifier} to apply (typically {@link AccessModifier#ACCESSIBLE}
     * or {@link AccessModifier#EXTENDABLE}).
     * @param className The internal name of the class to be modified (e.g., {@code com/example/MyClass}).
     * It is expected to be normalized (using '/' as a separator).
     */
    public ClassAccessWidener(AccessModifier modifier, String className) {
        super(modifier, className);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AccessModifier> allowedModifiers() {
        return List.of(AccessModifier.ACCESSIBLE, AccessModifier.EXTENDABLE);
    }
}
