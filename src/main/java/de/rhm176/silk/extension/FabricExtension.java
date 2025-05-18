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

import java.util.*;
import javax.inject.Inject;
import org.gradle.api.Action;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;

/**
 * Gradle extension for configuring the {@code fabric.mod.json} metadata file.
 * <p>
 * This extension allows developers to define all aspects of their mod's metadata
 * in a type-safe DSL within their {@code build.gradle.kts} or {@code build.gradle} file.
 * This is an alternative to manually writing one, with additional checks built-in.
 * <p>
 * The properties and methods provided correspond to the fields defined in the
 * <a href="https://fabricmc.net/wiki/documentation:fabric_mod_json">fabric.mod.json specification</a>.
 */
public abstract class FabricExtension {
    public static final int SCHEMA_VERSION = 1;

    private final ObjectFactory objectFactory;
    private final EntrypointContainerExtension entrypoints;

    /**
     * The mod identifier. A string matching the pattern {@code ^[a-z][a-z0-9-_]{1,63}$}.
     * This is a mandatory field. Defaults to {@code project.getName()}.
     * @return Property for the mod ID.
     */
    public abstract Property<String> getId();
    /**
     * The mod version. A string value, preferably matching Semantic Versioning 2.0.0.
     * This is a mandatory field.
     * @return Property for the mod version.
     */
    public abstract Property<String> getVersion();
    /**
     * The user-facing mod name. If not present, assumes it matches {@link #getId()}
     * @return Property for the mod name.
     */
    public abstract Property<String> getName();
    /**
     * The user-facing mod description. If not present, assumed to be an empty string.
     * @return Property for the mod description.
     */
    public abstract Property<String> getDescription();
    /**
     * Contains the direct authorship information.
     * @return ListProperty for authors.
     * @see PersonExtension
     */
    public abstract ListProperty<PersonExtension> getAuthors();
    /**
     * Contains the contributor information.
     * @return ListProperty for contributors.
     * @see PersonExtension
     */
    public abstract ListProperty<PersonExtension> getContributors();
    /**
     * Contains the licensing information. Can be a single license string or a list of license strings.
     * It is recommended to use <a href="https://spdx.org/licenses/">SPDX License Identifiers</a>.
     * @return ListProperty for license identifiers.
     */
    public abstract ListProperty<String> getLicenses();
    /**
     * Contains the contact information for the project as a whole.
     * This is a string-to-string dictionary. Defined keys include {@code "email"},
     * {@code "irc"}, {@code "homepage"}, {@code "issues"}, {@code "sources"}.
     * Mods may provide additional, non-standard keys.
     * @return MapProperty for project contact information.
     */
    public abstract MapProperty<String, String> getContact();
    /**
     * Contains an array of nested jars.
     * Each string points to a path from the mod's root to a nested JAR.
     * @return ListProperty for paths to nested JARs.
     */
    public abstract ListProperty<String> getJars();
    /**
     * A string-to-string dictionary, connecting language adapter namespaces to
     * their Java class implementations.
     * @return MapProperty for language adapters.
     */
    public abstract MapProperty<String, String> getLanguageAdapters();
    /**
     * Contains a list of mixin configuration files.
     * @return ListProperty for mixin configuration file names.
     */
    public abstract ListProperty<String> getMixins();
    /**
     * Dependencies that cause a hard failure if not met.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "depends" dependencies.
     */
    public abstract MapProperty<String, String> getDepends();
    /**
     * Dependencies that cause a soft failure (warning) if not met.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "recommends" dependencies.
     */
    public abstract MapProperty<String, String> getRecommends();
    /**
     * Dependencies that are not matched and are primarily used as metadata.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "suggests" dependencies.
     */
    public abstract MapProperty<String, String> getSuggests();
    /**
     * Dependencies for which a successful match causes a soft failure (warning).
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "conflicts" dependencies.
     */
    public abstract MapProperty<String, String> getConflicts();
    /**
     * Dependencies for which a successful match causes a hard failure.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "breaks" dependencies.
     */
    public abstract MapProperty<String, String> getBreaks();
    /**
     * A file path to an access widener file, relative to the mod root.
     * @return Property for the access widener file path.
     */
    public abstract Property<String> getAccessWidener();

    /**
     * The path to a single icon file (e.g., "assets/modid/icon.png").
     * Use this OR {@link #getIconSet()}, but not both. Setting one via DSL methods will clear the other.
     * If both are somehow set, the task will prioritize {@link #getIconSet()}.
     *
     * @return Property for a single icon file path.
     */
    public abstract Property<String> getIconFile();
    /**
     * A map of icon sizes (as string keys, e.g., "16", "32", "128") to their respective
     * icon file paths (e.g., "assets/modid/icon16.png").
     * Use this OR {@link #getIconFile()}. Setting one via DSL methods will clear the other.
     * This will take precedence over {@link #getIconFile()} if both are non-empty.
     *
     * @return MapProperty for an icon set mapping sizes to paths.
     */
    public abstract MapProperty<String, String> getIconSet();

    /**
     * A map for any other arbitrary custom fields to be added under the "custom" key in {@code fabric.mod.json}.
     * <p>
     * Keys should be strings. Values can be simple types (String, Number, Boolean),
     * Lists, or Maps, which will be serialized to their JSON equivalents.
     * It is recommended that custom keys be namespaced (e.g., {@code "yourmodid:yourCustomField": "value"}).
     * <p>Example in {@code build.gradle.kts}:</p>
     * <pre>
     * fabric {
     *     customData.put("my-mod-id:my-setting", "hello world")
     *     customData.put("my-mod-id:complex-setting", mapOf("a" to 1, "b" to true))
     * }
     * </pre>
     * @return MapProperty for other custom key-value data.
     */
    public abstract MapProperty<String, Object> getCustomData();

    /**
     * Constructs the FabricExtension. This is typically called by Gradle.
     *
     * @param objectFactory The Gradle {@link ObjectFactory} for creating managed objects.
     * @param project The current {@link Project}.
     */
    @Inject
    public FabricExtension(ObjectFactory objectFactory, Project project) {
        this.objectFactory = objectFactory;
        this.entrypoints = objectFactory.newInstance(EntrypointContainerExtension.class, objectFactory);

        this.getId().convention(project.getName());
    }

    /**
     * Provides access to configure the {@link EntrypointContainerExtension} for mod entrypoints.
     * <p>Example in {@code build.gradle.kts}:</p>
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
     * @return The {@link EntrypointContainerExtension} instance.
     */
    public EntrypointContainerExtension getEntrypoints() {
        return this.entrypoints;
    }

    /**
     * Configures mod entrypoints using an {@link Action}.
     * This allows for a DSL-like configuration block.
     * @param action The configuration action for the {@link EntrypointContainerExtension}.
     */
    public void entrypoints(Action<? super EntrypointContainerExtension> action) {
        action.execute(this.entrypoints);
    }

    /**
     * Sets the mod's icon to a single PNG file.
     * This will clear any previously defined icon set (map of sizes).
     *
     * @param path The path to the icon file, relative to the mod's root (e.g., "assets/mymodid/icon.png").
     */
    public void icon(String path) {
        if (path != null && !path.trim().isEmpty()) {
            getIconFile().set(path);
            getIconSet().empty();
            getIconSet().convention(Collections.emptyMap());
        } else {
            getIconFile().set((String) null);
        }
    }
    /**
     * Configures a set of icons for different sizes (e.g., "16" -> "path/to/icon16.png").
     * This will clear any previously defined single icon file path.
     * @param action Configuration action for the icon set map.
     */
    public void iconSet(Action<? super MapProperty<String, String>> action) {
        getIconFile().set((String) null);
        getIconFile().convention((String) null);
        getIconSet().empty();
        action.execute(getIconSet());
    }
    /**
     * Directly sets the map of icons for different sizes.
     * This will clear any previously defined single icon file path.
     * @param iconsMap A map where keys are size identifiers (e.g., "16") and values are paths to icon files.
     */
    public void iconSet(Map<String, String> iconsMap) {
        getIconFile().set((String) null);
        getIconFile().convention((String) null);
        if (iconsMap != null) {
            getIconSet().set(iconsMap);
        } else {
            getIconSet().empty();
        }
    }

    /**
     * Sets a single license for the mod. This will replace any previously set licenses.
     * @param licenseIdentifier The SPDX license identifier or custom license name.
     */
    public void license(String licenseIdentifier) {
        if (licenseIdentifier != null && !licenseIdentifier.trim().isEmpty()) {
            getLicenses().set(Collections.singletonList(licenseIdentifier));
        } else {
            getLicenses().set(Collections.emptyList());
        }
    }
    /**
     * Sets multiple licenses for the mod using varargs. This will replace any previously set licenses.
     * @param licenseIdentifiers Varargs of license identifiers.
     */
    public void licenses(String... licenseIdentifiers) {
        if (licenseIdentifiers != null) {
            getLicenses().set(Arrays.asList(licenseIdentifiers));
        } else {
            getLicenses().set(Collections.emptyList());
        }
    }
    /**
     * Sets multiple licenses for the mod from a list. This will replace any previously set licenses.
     * @param licenseList A list of license identifiers.
     */
    public void licenses(List<String> licenseList) {
        getLicenses().set(Objects.requireNonNullElse(licenseList, Collections.emptyList()));
    }
    /**
     * Adds a license to the list of licenses for the mod.
     * Use this if the mod is dual-licensed or has multiple license components.
     * @param licenseIdentifier The SPDX license identifier or custom license name.
     */
    public void addLicense(String licenseIdentifier) {
        if (licenseIdentifier != null && !licenseIdentifier.trim().isEmpty()) {
            getLicenses().add(licenseIdentifier);
        }
    }

    /**
     * Adds an author with only a name.
     * @param name The name of the author.
     */
    public void author(String name) {
        PersonExtension author = objectFactory.newInstance(PersonExtension.class);
        author.getName().set(name);

        getAuthors().add(author);
    }
    /**
     * Adds an author with a name and allows configuration of their contact details.
     * @param name The name of the author.
     * @param configureAction Configuration action for the author's details (e.g., setting contact info).
     */
    public void author(String name, Action<? super PersonExtension> configureAction) {
        PersonExtension author = objectFactory.newInstance(PersonExtension.class);
        author.getName().set(name);
        configureAction.execute(author);
        getAuthors().add(author);
    }

    /**
     * Adds a contributor with only a name.
     * @param name The name of the contributor.
     */
    public void contributor(String name) {
        PersonExtension contributor = objectFactory.newInstance(PersonExtension.class);
        contributor.getName().set(name);

        getContributors().add(contributor);
    }
    /**
     * Adds a contributor with a name and allows configuration of their contact details.
     * @param name The name of the contributor.
     * @param configureAction Configuration action for the contributor's details.
     */
    public void contributor(String name, Action<? super PersonExtension> configureAction) {
        PersonExtension contributor = objectFactory.newInstance(PersonExtension.class);
        contributor.getName().set(name);
        configureAction.execute(contributor);
        getContributors().add(contributor);
    }
}
