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
package de.rhm176.silk.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.rhm176.silk.extension.EntrypointContainerExtension;
import de.rhm176.silk.extension.EntrypointExtension;
import de.rhm176.silk.extension.FabricExtension;
import de.rhm176.silk.extension.PersonExtension;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

/**
 * Gradle task responsible for generating a {@code fabric.mod.json} file.
 * <p>
 * This task takes configuration inputs, typically wired from a {@link FabricExtension} instance,
 * validates them against the fabric.mod.json specification, and then constructs
 * the JSON file. If validation fails, the build is halted with an error detailing
 * all identified issues.
 *
 * @see FabricExtension
 * @see <a href="https://wiki.fabricmc.net/documentation:fabric_mod_json_spec">fabric.mod.json Specification</a>
 */
public abstract class GenerateFabricJsonTask extends DefaultTask {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private static final Pattern MOD_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9-_]{1,63}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,6}$");
    private static final Pattern ENTRYPOINT_VALUE_PATTERN = Pattern.compile(
            "^(\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*(\\.\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)*)(::\\p{javaJavaIdentifierStart}\\p{javaJavaIdentifierPart}*)?$");

    /**
     * The mod identifier. A string matching the pattern {@code ^[a-z][a-z0-9-_]{1,63}$}.
     * This is a mandatory field. Defaults to {@code project.getName()}.
     * @return Property for the mod ID.
     */
    @Input
    public abstract Property<String> getId();

    /**
     * The mod version. A string value, preferably matching Semantic Versioning 2.0.0.
     * This is a mandatory field.
     * @return Property for the mod version.
     */
    @Input
    public abstract Property<String> getVersion();

    /**
     * Controls whether the configured inputs for {@code fabric.mod.json} should be validated.
     * If true (default), extensive checks are performed. If false, validation is skipped.
     * @return Property for enabling/disabling validation.
     */
    @Input
    public abstract Property<Boolean> getShouldVerify();

    /**
     * The user-facing mod name. If not present, assumes it matches {@link #getId()}
     * @return Property for the mod name.
     */
    @Optional
    @Input
    public abstract Property<String> getModName();

    /**
     * The user-facing mod description. If not present, assumed to be an empty string.
     * @return Property for the mod description.
     */
    @Optional
    @Input
    public abstract Property<String> getModDescription();

    /**
     * Contains the direct authorship information.
     * @return ListProperty for authors.
     * @see PersonExtension
     */
    @Optional
    @Input
    public abstract ListProperty<PersonExtension> getAuthors();

    /**
     * Contains the contributor information.
     * @return ListProperty for contributors.
     * @see PersonExtension
     */
    @Optional
    @Input
    public abstract ListProperty<PersonExtension> getContributors();

    /**
     * Contains the licensing information. Can be a single license string or a list of license strings.
     * It is recommended to use <a href="https://spdx.org/licenses/">SPDX License Identifiers</a>.
     * @return ListProperty for license identifiers.
     */
    @Optional
    @Input
    public abstract ListProperty<String> getLicenses();

    /**
     * Contains the contact information for the project as a whole.
     * This is a string-to-string dictionary. Defined keys include {@code "email"},
     * {@code "irc"}, {@code "homepage"}, {@code "issues"}, {@code "sources"}.
     * Mods may provide additional, non-standard keys.
     * @return MapProperty for project contact information.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getContact();

    /**
     * Contains an array of nested jars.
     * Each string points to a path from the mod's root to a nested JAR.
     * @return ListProperty for paths to nested JARs.
     */
    @Optional
    @Input
    public abstract ListProperty<String> getJars();

    /**
     * A string-to-string dictionary, connecting language adapter namespaces to
     * their Java class implementations.
     * @return MapProperty for language adapters.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getLanguageAdapters();

    /**
     * Contains a list of mixin configuration files.
     * @return ListProperty for mixin configuration file names.
     */
    @Optional
    @Input
    public abstract ListProperty<String> getMixins();

    /**
     * Dependencies that cause a hard failure if not met.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "depends" dependencies.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getDepends();

    /**
     * Dependencies that cause a soft failure (warning) if not met.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "recommends" dependencies.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getRecommends();

    /**
     * Dependencies that are not matched and are primarily used as metadata.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "suggests" dependencies.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getSuggests();

    /**
     * Dependencies for which a successful match causes a soft failure (warning).
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "conflicts" dependencies.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getConflicts();

    /**
     * Dependencies for which a successful match causes a hard failure.
     * A string-to-string map where the key is the mod ID and the value is a version string.
     * @return MapProperty for "breaks" dependencies.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getBreaks();

    /**
     * A file path to an access widener file, relative to the mod root.
     * @return Property for the access widener file path.
     */
    @Optional
    @Input
    public abstract Property<String> getAccessWidener();

    /**
     * The path to a single icon file (e.g., "assets/modid/icon.png").
     * Use this OR {@link #getIconSet()}, but not both. Setting one via DSL methods will clear the other.
     * If both are somehow set, the task will prioritize {@link #getIconSet()}.
     *
     * @return Property for a single icon file path.
     */
    @Optional
    @Input
    public abstract Property<String> getIconFile();

    /**
     * A map of icon sizes (as string keys, e.g., "16", "32", "128") to their respective
     * icon file paths (e.g., "assets/modid/icon16.png").
     * Use this OR {@link #getIconFile()}. Setting one via DSL methods will clear the other.
     * This will take precedence over {@link #getIconFile()} if both are non-empty.
     *
     * @return MapProperty for an icon set mapping sizes to paths.
     */
    @Optional
    @Input
    public abstract MapProperty<String, String> getIconSet();

    /**
     * Provides the configuration for mod entrypoints. This is an optional, nested input object.
     * <p>
     * Entrypoints define the initial classes or methods that Fabric Loader will invoke
     * for different loading stages (e.g., "main", "preLaunch"). Each entrypoint
     * declaration within the container specifies a value (class or class::member reference)
     * and an optional language adapter.
     *
     * @return Property holding the {@link EntrypointContainerExtension} for configuring entrypoints.
     * @see FabricExtension#getEntrypoints()
     * @see EntrypointContainerExtension
     * @see EntrypointExtension
     */
    @Optional
    @Nested
    public abstract Property<EntrypointContainerExtension> getEntrypointsContainer();

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
    @Optional
    @Input
    public abstract MapProperty<String, Object> getCustomData();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getMainResourceDirectories();

    /**
     * The output file where the generated {@code fabric.mod.json} will be written.
     * This property is mandatory.
     * @return RegularFileProperty for the output JSON file.
     */
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /**
     * Executes the task action.
     * Performs validation of all configured inputs against the {@code fabric.mod.json} specification.
     * If validation passes, it constructs the JSON object and writes it to the output file.
     *
     * @throws GradleException if validation fails (listing all errors) or if an I/O error occurs.
     */
    @TaskAction
    public void execute() {
        List<String> validationErrors = new ArrayList<>();
        if (getShouldVerify().get()) validateAllInputs(validationErrors);

        if (!validationErrors.isEmpty()) {
            StringBuilder errorMessage = new StringBuilder("Invalid fabric.mod.json configuration:\n");
            for (String error : validationErrors) {
                errorMessage.append("  - ").append(error).append("\n");
            }
            throw new GradleException(errorMessage.toString());
        }

        constructAndWriteJson();
    }

    private void validateAllInputs(List<String> errors) {
        Set<File> mainResourceDirs = getMainResourceDirectories().getFiles();

        String id = getId().getOrNull();
        if (id == null || id.trim().isEmpty()) {
            errors.add("'id' is mandatory and cannot be empty.");
        } else if (!MOD_ID_PATTERN.matcher(id).matches()) {
            errors.add("Invalid 'id': \"" + id + "\". Must match pattern: " + MOD_ID_PATTERN.pattern());
        }

        String version = getVersion().getOrNull();
        if (version == null || version.trim().isEmpty()) {
            errors.add("'version' is mandatory and cannot be empty.");
        }

        validateOptionalStringProperty(errors, getModName(), "name");
        validateOptionalStringProperty(errors, getModDescription(), "description");

        validatePersonList(errors, getAuthors().getOrElse(Collections.emptyList()), "authors");
        validatePersonList(errors, getContributors().getOrElse(Collections.emptyList()), "contributors");

        validateStringList(errors, getLicenses().getOrElse(Collections.emptyList()), "licenses", false);

        validateContactMap(errors, getContact().getOrElse(Collections.emptyMap()), "contact (top-level)");

        if (getEntrypointsContainer().isPresent()) {
            validateEntrypoints(errors, getEntrypointsContainer().get());
        }

        validateStringList(errors, getJars().getOrElse(Collections.emptyList()), "jars[*].file", false);

        validateStringMap(
                errors, getLanguageAdapters().getOrElse(Collections.emptyMap()), "languageAdapters", false, false);

        List<String> mixins = getMixins().getOrElse(Collections.emptyList());
        for (String mixin : mixins) {
            if (mixin == null || mixin.trim().isEmpty()) {
                errors.add("Mixin entry cannot be null or empty.");
            } else if (!mixin.toLowerCase().endsWith(".json")) {
                errors.add("Mixin entry '" + mixin + "' should be a JSON file path.");
            }
        }
        validateFileResourcePathsList(errors, getMixins(), "mixins", ".json", mainResourceDirs);
        validateFileResourcePath(errors, getAccessWidener(), "accessWidener", null, mainResourceDirs);

        validateIcon(
                errors, getIconFile().getOrNull(), getIconSet().getOrElse(Collections.emptyMap()), mainResourceDirs);

        validateDependencyMap(errors, getDepends().getOrElse(Collections.emptyMap()), "depends");
        validateDependencyMap(errors, getRecommends().getOrElse(Collections.emptyMap()), "recommends");
        validateDependencyMap(errors, getSuggests().getOrElse(Collections.emptyMap()), "suggests");
        validateDependencyMap(errors, getConflicts().getOrElse(Collections.emptyMap()), "conflicts");
        validateDependencyMap(errors, getBreaks().getOrElse(Collections.emptyMap()), "breaks");

        Map<String, Object> customData = getCustomData().getOrElse(Collections.emptyMap());
        for (Map.Entry<String, Object> entry : customData.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                errors.add("Custom data key cannot be null or empty.");
            }
        }
    }

    private void validateFileResourcePath(
            List<String> errors,
            Property<String> pathProperty,
            String fieldName,
            String expectedExtension,
            Set<File> configuredResourceDirs) {
        if (!pathProperty.isPresent()) return;
        String path = pathProperty.getOrNull();
        if (path == null || path.trim().isEmpty()) {
            errors.add(String.format("Field '%s' path, if explicitly provided, cannot be empty.", fieldName));
            return;
        }

        if (expectedExtension != null && !path.toLowerCase().endsWith(expectedExtension.toLowerCase())) {
            errors.add(String.format("Field '%s' path '%s' must end with '%s'.", fieldName, path, expectedExtension));
        }
        validateFileResourcePathContent(errors, path, fieldName, configuredResourceDirs);
    }

    private void validateFileResourcePathsList(
            List<String> errors,
            ListProperty<String> listProperty,
            String fieldName,
            String expectedExtension,
            Set<File> configuredResourceDirs) {
        if (!listProperty.isPresent()) return;
        List<String> paths = listProperty.get();

        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            String itemContext = String.format("Entry #%d ('%s') in '%s' list", i + 1, path, fieldName);
            if (path == null || path.trim().isEmpty()) {
                errors.add(String.format("Path entry #%d in '%s' list cannot be null or empty.", i + 1, fieldName));
                continue;
            }
            if (expectedExtension != null && !path.toLowerCase().endsWith(expectedExtension.toLowerCase())) {
                errors.add(String.format(
                        "Entry '%s' (at index %d) in '%s' list must end with '%s'.",
                        path, i, fieldName, expectedExtension));
            }
            validateFileResourcePathContent(errors, path, itemContext, configuredResourceDirs);
        }
    }

    private void validateOptionalStringProperty(List<String> errors, Property<String> property, String fieldName) {
        if (property.isPresent() && (property.get().trim().isEmpty())) {
            errors.add(String.format("'%s' if present, cannot be empty.", fieldName));
        }
    }

    private void validateStringList(List<String> errors, List<String> list, String fieldName, boolean allowEmptyList) {
        if (list == null) return;

        for (int i = 0; i < list.size(); i++) {
            String item = list.get(i);
            if (item == null || (!allowEmptyList && item.trim().isEmpty())) {
                errors.add(String.format("Entry %d in '%s' list cannot be null or empty.", i + 1, fieldName));
            }
        }
    }

    private void validateStringMap(
            List<String> errors,
            Map<String, String> map,
            String fieldName,
            boolean allowEmptyKeys,
            boolean allowEmptyValues) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (!allowEmptyKeys
                    && (entry.getKey() == null || entry.getKey().trim().isEmpty())) {
                errors.add(String.format("Key in '%s' map cannot be null or empty.", fieldName));
            }
            if (!allowEmptyValues
                    && (entry.getValue() == null || entry.getValue().trim().isEmpty())) {
                errors.add(String.format(
                        "Value for key '%s' in '%s' map cannot be null or empty.", entry.getKey(), fieldName));
            }
        }
    }

    private void validateDependencyMap(List<String> errors, Map<String, String> map, String fieldName) {
        for (Map.Entry<String, String> entry : map.entrySet()) {
            String modId = entry.getKey();
            String versionRange = entry.getValue();
            if (modId == null || modId.trim().isEmpty()) {
                errors.add(String.format("Mod ID (key) in '%s' map cannot be null or empty.", fieldName));
            }
            if (versionRange == null || versionRange.trim().isEmpty()) {
                errors.add(String.format(
                        "Version range for mod ID '%s' in '%s' map cannot be null or empty.", modId, fieldName));
            }
        }
    }

    private void validatePersonList(List<String> errors, List<PersonExtension> persons, String fieldName) {
        for (PersonExtension person : persons) {
            String name = person.getName().getOrNull();
            if (name == null || name.trim().isEmpty()) {
                errors.add(String.format("Person 'name' in '%s' list is mandatory and cannot be empty.", fieldName));
            }
            validateContactMap(
                    errors,
                    person.getContact().getOrElse(Collections.emptyMap()),
                    fieldName + " entry '" + (name != null ? name : "unknown") + "' contact");
        }
    }

    private void validateContactMap(List<String> errors, Map<String, String> contactMap, String context) {
        for (Map.Entry<String, String> entry : contactMap.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (value == null || value.trim().isEmpty()) {
                errors.add(String.format("Contact '%s' in %s cannot have an empty value.", key, context));
                continue;
            }
            try {
                switch (key.toLowerCase()) {
                    case "email":
                        if (!EMAIL_PATTERN.matcher(value).matches()) {
                            errors.add(String.format("Invalid email format for '%s' in %s: %s", key, context, value));
                        }
                        break;
                    case "irc":
                        URI ircUri = new URI(value);
                        if (!"irc".equalsIgnoreCase(ircUri.getScheme())) {
                            errors.add(String.format(
                                    "Contact '%s' in %s must be a valid irc:// URL: %s", key, context, value));
                        }
                        break;
                    case "homepage":
                    case "issues":
                        URI httpUri = new URI(value);
                        if (!("http".equalsIgnoreCase(httpUri.getScheme())
                                || "https".equalsIgnoreCase(httpUri.getScheme()))) {
                            errors.add(String.format(
                                    "Contact '%s' in %s must be a valid HTTP/HTTPS URL: %s", key, context, value));
                        }
                        break;
                    case "sources":
                        new URI(value);
                        break;
                }
            } catch (URISyntaxException e) {
                errors.add(String.format("Invalid URL format for contact '%s' in %s: %s", key, context, value));
            }
        }
    }

    private void validateEntrypoints(List<String> errors, EntrypointContainerExtension container) {
        Map<String, ListProperty<EntrypointExtension>> types =
                container.getTypes().getOrElse(Collections.emptyMap());
        for (Map.Entry<String, ListProperty<EntrypointExtension>> entry : types.entrySet()) {
            String type = entry.getKey();
            List<EntrypointExtension> declarations = entry.getValue().getOrElse(Collections.emptyList());
            if (type == null || type.trim().isEmpty()) {
                errors.add("Entrypoint type (key) cannot be null or empty.");
            }
            if (declarations.isEmpty() && container.getTypes().get().containsKey(type)) {
                errors.add(
                        String.format("Entrypoint list for type '%s' cannot be empty if the type is declared.", type));
            }
            for (int i = 0; i < declarations.size(); i++) {
                EntrypointExtension decl = declarations.get(i);
                String value = decl.getValue().getOrNull();
                if (value == null || value.trim().isEmpty()) {
                    errors.add(String.format(
                            "Entrypoint declaration #%d for type '%s' must have a non-empty 'value'.", i + 1, type));
                } else if (!ENTRYPOINT_VALUE_PATTERN.matcher(value).matches()) {
                    errors.add(String.format(
                            "Invalid entrypoint 'value' format for type '%s', declaration #%d: \"%s\". Expected 'com.example.Class' or 'com.example.Class::member'.",
                            type, i + 1, value));
                }

                if (decl.getAdapter().isPresent()
                        && decl.getAdapter().get().trim().isEmpty()) {
                    errors.add(String.format(
                            "Entrypoint 'adapter' for type '%s', declaration #%d ('%s') cannot be an empty string if specified; omit or use 'default'.",
                            type, i + 1, value));
                }
            }
        }
    }

    private void validateFileResourcePathContent(
            List<String> errors, String path, String fieldNameForContext, Set<File> resourceDirectories) {
        if (path == null || path.trim().isEmpty()) {
            errors.add(String.format("Path for '%s' cannot be null or empty.", fieldNameForContext));
            return;
        }

        boolean foundInAnyResourceDir = false;
        File locatedFile = null;

        for (File resourceDir : resourceDirectories) {
            if (!resourceDir.isDirectory()) continue;

            File candidateFile = new File(resourceDir, path);
            if (candidateFile.exists()) {
                locatedFile = candidateFile;
                foundInAnyResourceDir = true;
                break;
            }
        }

        if (!foundInAnyResourceDir) {
            errors.add(String.format(
                    "Field '%s' references non-existent file: '%s'. Searched in configured resource directories: %s",
                    fieldNameForContext,
                    path,
                    resourceDirectories.stream().map(File::getAbsolutePath).collect(Collectors.joining(", "))));
            return;
        }

        if (!locatedFile.isFile()) {
            errors.add(String.format(
                    "Field '%s' path '%s' (found at %s) is not a file.",
                    fieldNameForContext, path, locatedFile.getAbsolutePath()));
        }

        boolean safelyLocated = false;
        try {
            String canonicalLocatedPath = locatedFile.getCanonicalPath();
            for (File resourceDir : resourceDirectories) {
                if (resourceDir.isDirectory()) {
                    String canonicalResourceDirPath = resourceDir.getCanonicalPath();
                    if (canonicalLocatedPath.startsWith(canonicalResourceDirPath + File.separator)
                            || canonicalLocatedPath.equals(canonicalResourceDirPath)) {
                        safelyLocated = true;
                        break;
                    }
                }
            }
            if (!safelyLocated) {
                errors.add(String.format(
                        "Field '%s' path '%s' (resolved to %s) appears to be outside configured resource directories. Path traversal suspected.",
                        fieldNameForContext, path, canonicalLocatedPath));
            }
        } catch (IOException e) {
            errors.add(String.format(
                    "Could not verify canonical path for field '%s' ('%s') due to an error: %s",
                    fieldNameForContext, path, e.getMessage()));
        }
    }

    private void validateIcon(
            List<String> errors, String iconFile, Map<String, String> iconSet, Set<File> resourceDirectories) {
        boolean iconFilePresent = iconFile != null && !iconFile.trim().isEmpty();
        boolean iconSetPresent = !iconSet.isEmpty();

        if (iconFilePresent && iconSetPresent) {
            errors.add("Both 'iconFile' and 'iconSet' are defined. Please use only one.");
        }

        if (iconSetPresent) {
            for (Map.Entry<String, String> entry : iconSet.entrySet()) {
                String sizeKey = entry.getKey();
                String path = entry.getValue();
                if (sizeKey == null || sizeKey.trim().isEmpty()) {
                    errors.add("Icon set key (size) cannot be null or empty.");
                } else {
                    try {
                        Integer.parseInt(sizeKey);
                    } catch (NumberFormatException e) {
                        errors.add("Icon set key (size) '" + sizeKey + "' must be a number string.");
                    }
                }

                if (path == null || path.trim().isEmpty()) {
                    errors.add("Icon set path for size '" + sizeKey + "' cannot be null or empty.");
                } else {
                    if (!path.toLowerCase().endsWith(".png")) {
                        errors.add("Icon set path for size '" + sizeKey + "' ('" + path + "') must be a .png file.");
                    }
                    validateFileResourcePathContent(
                            errors, path, "icon set (size " + sizeKey + ")", resourceDirectories);
                }
            }
        } else if (iconFilePresent) {
            if (!iconFile.toLowerCase().endsWith(".png")) {
                errors.add("Icon file path '" + iconFile + "' must be a .png file.");
            }
            validateFileResourcePathContent(errors, iconFile, "iconFile", resourceDirectories);
        }
    }

    private void constructAndWriteJson() {
        ObjectNode rootNode = OBJECT_MAPPER.createObjectNode();

        rootNode.put("schemaVersion", FabricExtension.SCHEMA_VERSION);
        rootNode.put("id", getId().get());
        rootNode.put("version", getVersion().get());

        rootNode.put("environment", "*");

        if (getEntrypointsContainer().isPresent()) {
            EntrypointContainerExtension entrypointsContainer =
                    getEntrypointsContainer().get();
            Map<String, ListProperty<EntrypointExtension>> entrypointTypes =
                    entrypointsContainer.getTypes().getOrElse(Collections.emptyMap());
            if (!entrypointTypes.isEmpty()) {
                ObjectNode entrypointsRootNode = rootNode.putObject("entrypoints");
                entrypointTypes.forEach((type, declarationsListProperty) -> {
                    List<EntrypointExtension> declarations =
                            declarationsListProperty.getOrElse(Collections.emptyList());
                    if (!declarations.isEmpty()) {
                        ArrayNode typeArrayNode = entrypointsRootNode.putArray(type);
                        for (EntrypointExtension decl : declarations) {
                            String value = decl.getValue().get();
                            String adapter = decl.getAdapter().getOrNull();
                            if (adapter != null && !adapter.trim().isEmpty() && !adapter.equals("default")) {
                                ObjectNode entryObject = typeArrayNode.addObject();
                                entryObject.put("adapter", adapter);
                                entryObject.put("value", value);
                            } else {
                                typeArrayNode.add(value);
                            }
                        }
                    }
                });
                if (entrypointsRootNode.isEmpty()) {
                    rootNode.remove("entrypoints");
                }
            }
        }

        List<String> jarPaths = getJars().getOrElse(Collections.emptyList());
        if (!jarPaths.isEmpty()) {
            ArrayNode jarsArray = rootNode.putArray("jars");
            jarPaths.forEach(path -> jarsArray.addObject().put("file", path));
        }

        putMapIfPresent(rootNode, "languageAdapters", getLanguageAdapters());
        putListIfPresent(rootNode, "mixins", getMixins());
        putIfPresent(rootNode, "accessWidener", getAccessWidener());

        putMapIfPresent(rootNode, "depends", getDepends());
        putMapIfPresent(rootNode, "recommends", getRecommends());
        putMapIfPresent(rootNode, "suggests", getSuggests());
        putMapIfPresent(rootNode, "conflicts", getConflicts());
        putMapIfPresent(rootNode, "breaks", getBreaks());

        putIfPresent(rootNode, "name", getModName());
        putIfPresent(rootNode, "description", getModDescription());
        putPersonListIfPresent(rootNode, "authors", getAuthors());
        putPersonListIfPresent(rootNode, "contributors", getContributors());

        List<String> licenses = getLicenses().getOrElse(Collections.emptyList());
        if (!licenses.isEmpty()) {
            if (licenses.size() == 1) {
                rootNode.put("license", licenses.get(0));
            } else {
                ArrayNode licensesArray = rootNode.putArray("license");
                licenses.forEach(licensesArray::add);
            }
        }

        Map<String, String> iconSet = getIconSet().getOrElse(Collections.emptyMap());
        String iconFile = getIconFile().getOrNull();
        if (!iconSet.isEmpty()) {
            ObjectNode iconSetNode = rootNode.putObject("icon");
            iconSet.forEach(iconSetNode::put);
        } else if (iconFile != null && !iconFile.trim().isEmpty()) {
            rootNode.put("icon", iconFile);
        }

        Map<String, Object> customData = getCustomData().getOrElse(Collections.emptyMap());
        if (!customData.isEmpty()) {
            ObjectNode customNode = rootNode.putObject("custom");
            customData.forEach(customNode::putPOJO);
        }

        File outputFile = getOutputFile().get().getAsFile();
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new GradleException(
                        "Could not create output directory for generated fabric.mod.json: " + parentDir);
            }
        }
        try {
            OBJECT_MAPPER.writeValue(outputFile, rootNode);
        } catch (IOException e) {
            throw new GradleException("Failed to write fabric.mod.json to " + outputFile.getAbsolutePath(), e);
        }
    }

    private void putIfPresent(ObjectNode node, String fieldName, Property<?> property) {
        if (property.isPresent()) {
            Object value = property.get();
            if (value instanceof String) {
                if (!((String) value).trim().isEmpty()) node.put(fieldName, (String) value);
            } else if (value instanceof Integer) {
                node.put(fieldName, (Integer) value);
            } else if (value instanceof Boolean) {
                node.put(fieldName, (Boolean) value);
            }
        }
    }

    private void putMapIfPresent(ObjectNode node, String fieldName, MapProperty<String, String> mapProperty) {
        Map<String, String> map = mapProperty.getOrElse(Collections.emptyMap());
        if (!map.isEmpty()) {
            ObjectNode mapNode = node.putObject(fieldName);
            map.forEach((k, v) -> {
                if (k != null && !k.trim().isEmpty() && v != null && !v.trim().isEmpty()) {
                    mapNode.put(k, v);
                }
            });
            if (mapNode.isEmpty()) {
                node.remove(fieldName);
            }
        }
    }

    private void putListIfPresent(ObjectNode node, String fieldName, ListProperty<String> listProperty) {
        List<String> list = listProperty.getOrElse(Collections.emptyList());
        if (!list.isEmpty()) {
            ArrayNode arrayNode = node.putArray(fieldName);
            list.forEach(item -> {
                if (item != null && !item.trim().isEmpty()) {
                    arrayNode.add(item);
                }
            });
            if (arrayNode.isEmpty()) {
                node.remove(fieldName);
            }
        }
    }

    private void putPersonListIfPresent(
            ObjectNode node, String fieldName, ListProperty<PersonExtension> personListProperty) {
        List<PersonExtension> persons = personListProperty.getOrElse(Collections.emptyList());
        if (!persons.isEmpty()) {
            ArrayNode arrayNode = node.putArray(fieldName);
            for (PersonExtension personConfig : persons) {
                String name = personConfig.getName().getOrNull();
                if (name == null || name.trim().isEmpty()) continue;

                Map<String, String> contactInfo = personConfig.getContact().getOrElse(Collections.emptyMap());
                if (contactInfo.isEmpty()) {
                    arrayNode.add(name);
                } else {
                    ObjectNode personObject = arrayNode.addObject();
                    personObject.put("name", name);
                    ObjectNode contactNode = personObject.putObject("contact");
                    contactInfo.forEach((k, v) -> {
                        if (k != null
                                && !k.trim().isEmpty()
                                && v != null
                                && !v.trim().isEmpty()) {
                            contactNode.put(k, v);
                        }
                    });
                    if (contactNode.isEmpty()) {
                        personObject.remove("contact");
                    }
                }
            }
            if (arrayNode.isEmpty()) {
                node.remove(fieldName);
            }
        }
    }
}
