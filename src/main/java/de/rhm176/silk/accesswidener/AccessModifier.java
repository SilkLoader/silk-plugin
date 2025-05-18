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

/**
 * Defines the types of access modifications that can be applied by an Access Widener rule.
 * Each modifier has specific effects on classes, methods, or fields as defined by the
 * Fabric Access Widener specification.
 *
 * @see AccessWidenerRule
 * @see <a href="https://wiki.fabricmc.net/tutorial:accesswideners#access_changes">FabricMC Access Widener Specification - Access Changes</a>
 */
public enum AccessModifier {
    /**
     * The {@code accessible} modifier is used when you want to access a class, field, or method
     * from another class.
     * <ul>
     * <li><b>Classes:</b> Made {@code public}.</li>
     * <li><b>Methods:</b> Made {@code public}. If the method was originally {@code private},
     * it will also be made {@code final}.</li>
     * <li><b>Fields:</b> Made {@code public}.</li>
     * </ul>
     * Making a method or field {@code accessible} also transitively makes its containing class {@code accessible}.
     */
    ACCESSIBLE("accessible"),
    /**
     * The {@code extendable} modifier is used where you want to extend a class or override a method.
     * <ul>
     * <li><b>Classes:</b> Made {@code public} and the {@code final} modifier is removed.</li>
     * <li><b>Methods:</b> Made {@code protected} (if not already {@code public}) and the {@code final}
     * modifier is removed.</li>
     * </ul>
     * Making a method {@code extendable} also transitively makes its containing class {@code extendable}.
     */
    EXTENDABLE("extendable"),
    /**
     * The {@code mutable} modifier is used when you want to mutate a {@code final} field.
     * <ul>
     * <li><b>Fields:</b> The {@code final} modifier is removed.</li>
     * </ul>
     * This modifier applies only to fields. To make a {@code private final} field both
     * accessible (e.g., public) and mutable, two separate directives (one {@code accessible}
     * and one {@code mutable}) are required for that field.
     */
    MUTABLE("mutable");

    private final String id;

    AccessModifier(String id) {
        this.id = id;
    }

    /**
     * Gets the string identifier of this access modifier as used in {@code .accesswidener} files
     * (e.g., "accessible", "extendable", "mutable").
     *
     * @return The string identifier of the modifier.
     */
    public String getId() {
        return id;
    }

    /**
     * Retrieves an {@code AccessModifier} enum constant by its string identifier.
     * This method is case-sensitive.
     *
     * @param id The string identifier (e.g., "accessible", "extendable", "mutable").
     * @return The corresponding {@code AccessModifier} enum constant, or {@code null} if no match is found.
     */
    public static AccessModifier byId(String id) {
        for (AccessModifier mod : AccessModifier.values()) if (mod.getId().equals(id)) return mod;

        return null;
    }
}
