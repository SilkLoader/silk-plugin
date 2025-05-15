package de.rhm176.silk;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.util.Collections;

/**
 * Configuration for Vineflower decompiler settings within the Silk plugin.
 * <p>
 * Accessed via the {@code silk.vineflower} block in a Gradle build script:
 * <pre>
 * silk {
 *     vineflower {
 *
 *     }
 * }
 * </pre>
 */
public abstract class VineflowerExtension {
    /**
     * The default version of the Vineflower decompiler to be used if not otherwise specified.
     * Current default is {@value}.
     */
    public static final String DEFAULT_VINEFLOWER_VERSION = "1.11.1";

    private final Property<String> version;
    private final ListProperty<String> args;

    /**
     * Constructs a new VineflowerExtension.
     * Gradle's {@link ObjectFactory} is used to create managed properties.
     *
     * @param objectFactory Gradle's {@link ObjectFactory} for creating domain objects.
     */
    @Inject
    public VineflowerExtension(ObjectFactory objectFactory) {
        this.version = objectFactory.property(String.class).convention(DEFAULT_VINEFLOWER_VERSION);
        this.args = objectFactory.listProperty(String.class).convention(Collections.emptyList());
    }

    /**
     * The version of the Vineflower decompiler to use.
     * Defaults to {@value #DEFAULT_VINEFLOWER_VERSION}.
     *
     * @return A {@link Property} for the Vineflower version string.
     */
    public Property<String> getVersion() {
        return version;
    }

    /**
     * A list of custom arguments to pass to the Vineflower decompiler.
     * These arguments are added to the default set of arguments used by the {@link GenerateSourcesTask}.
     * <p>
     * Example:
     * <pre>
     * args.add("-cfg=true")
     * args.addAll("-lvt=true", "-hdc=0")
     * </pre>
     *
     * @return A {@link ListProperty} for custom Vineflower arguments.
     */
    public ListProperty<String> getArgs() {
        return args;
    }
}
