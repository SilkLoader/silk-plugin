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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.gradle.api.GradleException;

/**
 * Parses a v2 Access Widener file and stores the transformation rules.
 * <p>
 * An Access Widener file allows mods to increase the visibility or remove the {@code final} modifier
 * from classes, methods, and fields in a target JAR (e.g., the game JAR).
 * This class expects the Access Widener format as defined by FabricMC.
 * <p>
 * The header of the file must be {@code accessWidener v2 official}.
 * Each subsequent line defines a rule, for example:
 * <ul>
 * <li>{@code accessible class com/example/MyClass}</li>
 * <li>{@code extendable method com/example/MyClass myMethod ()V}</li>
 * <li>{@code mutable field com/example/MyClass myField I}</li>
 * </ul>
 * Comments start with {@code #}.
 *
 * @see <a href="https://wiki.fabricmc.net/tutorial:accesswideners">FabricMC Access Wideners</a>
 */
public class AccessWidener {
    private final List<AccessWidenerRule> rules;

    /**
     * Parses Access Widener rules from an InputStream.
     * @param inputStream The stream containing the Access Widener data.
     * @param sourceDescription A description of the source (e.g., file path or JAR entry path) for logging.
     * @throws IOException If an I/O error occurs reading the stream.
     * @throws GradleException If the format is invalid.
     */
    public AccessWidener(InputStream inputStream, String sourceDescription) throws IOException {
        rules = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        String header = lines.remove(0).trim();

        if (lines.isEmpty()) {
            throw new GradleException(String.format("AccessWidener file '%s' is empty.", sourceDescription));
        }

        String[] headerParts = header.split("\\s+");
        if (headerParts.length < 3 || !"accessWidener".equals(headerParts[0]) || !"v2".equals(headerParts[1])) {
            throw new GradleException(String.format(
                    "Invalid AccessWidener header in '%s'. Expected 'accessWidener v2 <namespace>', but found: '%s'",
                    sourceDescription, header));
        }
        if (!"named".equals(headerParts[2])) {
            throw new GradleException(String.format(
                    "Unsupported AccessWidener namespace '%s' in header of '%s'. Expected 'named'. Header: '%s'",
                    headerParts[2], sourceDescription, header));
        }

        int lineNum = 1;
        for (String line : lines) {
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("#") || trimmedLine.isEmpty()) continue;

            String[] parts = trimmedLine.split("\\s+");
            if (parts.length < 3) {
                throw new GradleException(String.format(
                        "Malformed rule on line %d in '%s': Too few parts. Expected at least '<access> <type> <className>'. Line: '%s'",
                        lineNum, sourceDescription, line));
            }

            AccessModifier modifier = AccessModifier.byId(parts[0]);
            if (modifier == null) {
                throw new GradleException(String.format(
                        "Invalid access modifier '%s' on line %d in '%s'. Allowed: accessible, extendable, mutable. Line: '%s'",
                        parts[0], lineNum, sourceDescription, line));
            }

            AccessWidenerRule rule;
            switch (parts[1]) {
                case "class": {
                    if (parts.length != 3) {
                        throw new GradleException(String.format(
                                "Malformed 'class' rule on line %d in '%s'. Expected 3 parts: '<access> class <className>'. Got %d parts. Line: '%s'",
                                lineNum, sourceDescription, parts.length, line));
                    }
                    rule = new ClassAccessWidener(modifier, parts[2]);
                    break;
                }
                case "method": {
                    if (parts.length != 5) {
                        throw new GradleException(String.format(
                                "Malformed 'method' rule on line %d in '%s'. Expected 5 parts: '<access> method <className> <methodName> <methodDesc>'. Got %d parts. Line: '%s'",
                                lineNum, sourceDescription, parts.length, line));
                    }
                    rule = new MethodAccessWidener(modifier, parts[2], parts[3], parts[4]);
                    break;
                }
                case "field": {
                    if (parts.length != 5) {
                        throw new GradleException(String.format(
                                "Malformed 'field' rule on line %d in '%s'. Expected 5 parts: '<access> field <className> <fieldName> <fieldDesc>'. Got %d parts. Line: '%s'",
                                lineNum, sourceDescription, parts.length, line));
                    }
                    rule = new FieldAccessWidener(modifier, parts[2], parts[3], parts[4]);
                    break;
                }
                default: {
                    throw new GradleException(String.format(
                            "Unknown target type '%s' on line %d in '%s'. Allowed target types: class, method, field. Line: '%s'",
                            parts[1], lineNum, sourceDescription, line));
                }
            }

            if (!rule.allowedModifiers().contains(modifier)) {
                throw new GradleException(String.format(
                        "Access modifier '%s' is not permitted for target type '%s' on line %d in '%s'. Allowed for %s: %s. Line: '%s'",
                        modifier.getId(),
                        parts[1],
                        lineNum,
                        sourceDescription,
                        parts[1],
                        rule.allowedModifiers(),
                        line));
            }
            rules.add(rule);

            lineNum++;
        }
    }

    /**
     * Returns a list of the parsed {@link AccessWidenerRule}s.
     *
     * @return A list of rules.
     */
    public List<AccessWidenerRule> getRules() {
        return List.copyOf(rules);
    }

    // TODO:
    /**
     * Verifies the parsed Access Widener rules to check if targets exist and if the wideners are necessary.
     *
     * @return A list of strings, where each string is an error or warning message found during verification.
     * An empty list indicates no issues were found.
     */
    public List<String> verify() {
        return List.of();
    }
}
