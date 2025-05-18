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
 * Represents an Access Widener rule specifically targeting a field.
 * <p>
 * This rule defines how the access or {@code final} status (mutability) of a specific field
 * should be modified. It stores the target class name, field name, field descriptor,
 * and the {@link AccessModifier} to be applied (e.g., {@code accessible} or {@code mutable}).
 * <p>
 * An example line in an {@code .accesswidener} file for a field rule:
 * <pre>{@code accessible field com/example/MyClass myField Ljava/lang/String;}</pre>
 * or
 * <pre>{@code mutable field com/example/MyClass anIntField I}</pre>
 *
 * @see AccessWidenerRule
 * @see AccessModifier
 * @see de.rhm176.silk.task.TransformClassesTask
 */
public final class FieldAccessWidener extends AccessWidenerRule {
    private final String fieldName;
    private final String fieldDescriptor;

    /**
     * Constructs a new rule for widening access or modifying a field.
     *
     * @param modifier The {@link AccessModifier} to apply (typically {@link AccessModifier#ACCESSIBLE}
     * or {@link AccessModifier#MUTABLE}).
     * @param className The internal name of the class containing the field (e.g., {@code com/example/MyClass}).
     * It is expected to be normalized (using '/' as a separator).
     * @param fieldName The name of the field to be modified.
     * @param fieldDescriptor The JVM descriptor of the field (e.g., {@code I} for int, {@code Ljava/lang/String;} for String).
     */
    public FieldAccessWidener(AccessModifier modifier, String className, String fieldName, String fieldDescriptor) {
        super(modifier, className);

        this.fieldName = fieldName;
        this.fieldDescriptor = fieldDescriptor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<AccessModifier> allowedModifiers() {
        return List.of(AccessModifier.ACCESSIBLE, AccessModifier.MUTABLE);
    }

    /**
     * Gets the name of the field targeted by this rule.
     *
     * @return The name of the target field.
     */
    public String getFieldName() {
        return fieldName;
    }

    /**
     * Gets the JVM descriptor of the field targeted by this rule
     * (e.g., {@code I} for an integer, {@code Ljava/lang/String;} for a String object).
     *
     * @return The JVM descriptor of the target field.
     */
    public String getFieldDescriptor() {
        return fieldDescriptor;
    }
}
