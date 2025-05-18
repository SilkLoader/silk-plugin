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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;

public abstract class GenerateFabricJsonTask extends DefaultTask {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Input
    public abstract Property<String> getId();

    @Input
    public abstract Property<String> getVersion();

    @Optional
    @Input
    public abstract Property<String> getModName();

    @Optional
    @Input
    public abstract Property<String> getModDescription();

    @Optional
    @Input
    public abstract ListProperty<PersonExtension> getAuthors();

    @Optional
    @Input
    public abstract ListProperty<PersonExtension> getContributors();

    @Optional
    @Input
    public abstract ListProperty<String> getLicenses();

    @Optional
    @Input
    public abstract MapProperty<String, String> getContact();

    @Optional
    @Input
    public abstract ListProperty<String> getJars();

    @Optional
    @Input
    public abstract MapProperty<String, String> getLanguageAdapters();

    @Optional
    @Input
    public abstract ListProperty<String> getMixins();

    @Optional
    @Input
    public abstract MapProperty<String, String> getDepends();

    @Optional
    @Input
    public abstract MapProperty<String, String> getRecommends();

    @Optional
    @Input
    public abstract MapProperty<String, String> getSuggests();

    @Optional
    @Input
    public abstract MapProperty<String, String> getConflicts();

    @Optional
    @Input
    public abstract MapProperty<String, String> getBreaks();

    @Optional
    @Input
    public abstract Property<String> getAccessWidener();

    @Optional
    @Input
    public abstract Property<String> getIconFile();

    @Optional
    @Input
    public abstract MapProperty<String, String> getIconSet();

    @Optional
    @Nested
    public abstract Property<EntrypointContainerExtension> getEntrypointsContainer();

    @Optional
    @Input
    public abstract MapProperty<String, Object> getCustomData();

    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    private void putIfPresent(ObjectNode node, String fieldName, Property<?> property) {
        if (property.isPresent()) {
            Object value = property.get();
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                node.put(fieldName, (String) value);
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
            map.forEach(mapNode::put);
        }
    }

    private void putListIfPresent(ObjectNode node, String fieldName, ListProperty<String> listProperty) {
        List<String> list = listProperty.getOrElse(Collections.emptyList());
        if (!list.isEmpty()) {
            ArrayNode arrayNode = node.putArray(fieldName);
            list.forEach(arrayNode::add);
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
                    contactInfo.forEach(contactNode::put);
                }
            }
            if (arrayNode.isEmpty()) {
                node.remove(fieldName);
            }
        }
    }

    @TaskAction
    public void generate() { // TODO: probably throw error if fabric extension isn't present or null or anything weird
        ObjectNode rootNode = OBJECT_MAPPER.createObjectNode();

        rootNode.put("schemaVersion", FabricExtension.SCHEMA_VERSION);
        rootNode.put("id", getId().get());
        rootNode.put("version", getVersion().get());
        rootNode.put("environment", "*");

        if (getEntrypointsContainer().isPresent()) {
            Map<String, ListProperty<EntrypointExtension>> entrypointTypes =
                    getEntrypointsContainer().get().getTypes().getOrElse(Collections.emptyMap());
            if (!entrypointTypes.isEmpty()) {
                ObjectNode entrypointsRootNode = rootNode.putObject("entrypoints");
                entrypointTypes.forEach((type, declarationsListProperty) -> {
                    List<EntrypointExtension> declarations =
                            declarationsListProperty.getOrElse(Collections.emptyList());
                    if (!declarations.isEmpty()) {
                        ArrayNode typeArrayNode = entrypointsRootNode.putArray(type);
                        for (EntrypointExtension decl : declarations) {
                            String value = decl.getValue().getOrNull();
                            if (value == null || value.trim().isEmpty()) continue;

                            String adapter = decl.getAdapter().getOrNull();
                            if (adapter != null && !adapter.trim().isEmpty() && !adapter.equals("default")) {
                                ObjectNode entryObject = typeArrayNode.addObject();
                                entryObject.put("adapter", adapter);
                                entryObject.put("value", value);
                            } else {
                                typeArrayNode.add(value);
                            }
                        }
                        if (typeArrayNode.isEmpty()) {
                            entrypointsRootNode.remove(type);
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

        Map<String, Object> otherCustomData = getCustomData().getOrElse(Collections.emptyMap());
        if (!otherCustomData.isEmpty()) {
            ObjectNode customNode = rootNode.putObject("custom");

            otherCustomData.forEach(customNode::putPOJO);
        }

        File outputFile = getOutputFile().get().getAsFile();
        try {
            OBJECT_MAPPER.writeValue(outputFile, rootNode);
        } catch (IOException e) {
            throw new GradleException("Failed to write fabric.mod.json", e);
        }
    }
}
