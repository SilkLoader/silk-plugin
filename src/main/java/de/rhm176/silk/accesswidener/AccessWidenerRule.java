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
 * Abstract base class for all types of Access Widener rules.
 * <p>
 * An Access Widener rule defines a requested modification to the accessibility
 * or finality of a class, method, or field. Each rule consists of an
 * {@link AccessModifier} (e.g., accessible, extendable, mutable) and details
 * about the target element.
 * <p>
 * Subclasses like {@link ClassAccessWidener}, {@link MethodAccessWidener}, and
 * {@link FieldAccessWidener} provide concrete implementations for specific target types.
 *
 * @see AccessWidener
 * @see AccessModifier
 * @see ClassAccessWidener
 * @see MethodAccessWidener
 * @see FieldAccessWidener
 * @see <a href="https://wiki.fabricmc.net/tutorial:accesswideners">FabricMC Access Wideners</a>
 */
public abstract sealed class AccessWidenerRule permits ClassAccessWidener, MethodAccessWidener, FieldAccessWidener {
    private final AccessModifier modifier;
    private final String className;

    /**
     * Abstract method to be implemented by subclasses, defining which {@link AccessModifier}s
     * are applicable to the specific type of rule (class, method, or field).
     *
     * @return A list of {@link AccessModifier}s that are permitted for this rule type.
     */
    public abstract List<AccessModifier> allowedModifiers();

    /**
     * Constructs a new AccessWidenerRule.
     *
     * @param modifier The {@link AccessModifier} specifying the type of access change
     * (e.g., {@link AccessModifier#ACCESSIBLE}). Must not be null.
     * @param className The internal name of the class targeted by this rule or
     * containing the targeted member (e.g., {@code com/example/MyClass}).
     * This is expected to use '/' as a package separator. Must not be null.
     */
    public AccessWidenerRule(AccessModifier modifier, String className) {
        this.modifier = modifier;
        this.className = className;
    }

    /**
     * Gets the {@link AccessModifier} associated with this rule.
     * This indicates the type of access change requested (e.g., accessible, extendable, mutable).
     *
     * @return The {@link AccessModifier} for this rule.
     */
    public AccessModifier getModifier() {
        return modifier;
    }

    /**
     * Gets the internal name of the class targeted by this rule or containing the targeted member.
     * For example, {@code com/example/MyClass}.
     *
     * @return The internal name of the class.
     */
    public String getClassName() {
        return className;
    }
}
