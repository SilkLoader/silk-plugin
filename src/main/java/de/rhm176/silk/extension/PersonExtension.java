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

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * Represents a person (an author or contributor) as defined in the {@code fabric.mod.json} specification.
 * <p>
 * This class provides properties for both {@code name} and {@code contact}.
 *
 * @see FabricExtension#getAuthors()
 * @see FabricExtension#getContributors()
 * @see <a href="https://fabricmc.net/wiki/documentation:fabric_mod_json">fabric.mod.json specification</a>
 */
public abstract class PersonExtension {
    /**
     * The real name, or username, of the person. This field is mandatory.
     *
     * @return A {@link Property} for the person's name.
     */
    public abstract Property<String> getName();
    /**
     * An optional string-to-string dictionary containing contact information for the person.
     * <p>
     * Defined keys in the specification include {@code "email"}, {@code "irc"},
     * {@code "homepage"}, {@code "issues"}, {@code "sources"}.
     * Custom keys (e.g., {@code "discord"}, {@code "twitter"}) may also be used and
     * should ideally be valid URLs.
     *
     * @return A {@link MapProperty} for the person's contact information.
     */
    public abstract MapProperty<String, String> getContact();
}
