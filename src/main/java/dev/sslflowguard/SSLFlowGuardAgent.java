package dev.sslflowguard;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * Entry point for the SSLFlow Guard Java agent.
 */
public final class SSLFlowGuardAgent {
    private static final String TARGET_BINARY_NAME =
            "jdk.internal.net.http.common.SSLFlowDelegate$Reader";
    private static final String TARGET_RESOURCE =
            "jdk/internal/net/http/common/SSLFlowDelegate$Reader.class";

    private SSLFlowGuardAgent() { }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation, true);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        install(agentArgs, instrumentation, false);
    }

    private static void install(String agentArgs, Instrumentation instrumentation, boolean startup) {
        final AgentOptions options;
        try {
            options = AgentOptions.parse(agentArgs);
        } catch (RuntimeException e) {
            fatal("Invalid agent options", e);
            return; // unreachable
        }

        // Validate the exact JDK implementation bytes before the application starts using
        // java.net.http. This prevents a future vendor/JDK layout change from silently
        // leaving the workaround inactive.
        preflightCurrentJdk();

        SSLFlowGuardTransformer transformer = new SSLFlowGuardTransformer(options);
        instrumentation.addTransformer(transformer, true);

        Class<?> alreadyLoaded = findLoadedTarget(instrumentation);
        boolean retransformed = false;
        if (alreadyLoaded != null) {
            if (!instrumentation.isRetransformClassesSupported()
                    || !instrumentation.isModifiableClass(alreadyLoaded)) {
                fatal("Target class is already loaded but cannot be retransformed: "
                        + TARGET_BINARY_NAME, null);
            }
            try {
                instrumentation.retransformClasses(alreadyLoaded);
                retransformed = true;
            } catch (UnmodifiableClassException | RuntimeException e) {
                fatal("Failed to retransform already-loaded target class", e);
            }
        }

        if (!options.quiet) {
            String mode = options.debug ? "debug" : "production";
            String phase = startup ? "armed at JVM startup" : "attached dynamically";
            if (retransformed) {
                System.err.printf(
                        "[SSLFlowGuard] %s (%s), Java %s; already-loaded target retransformed.%n",
                        phase, mode, Runtime.version());
            } else {
                System.err.printf(
                        "[SSLFlowGuard] %s (%s), Java %s. Waiting for %s to load.%n",
                        phase, mode, Runtime.version(), TARGET_BINARY_NAME);
            }
        }
    }

    private static void preflightCurrentJdk() {
        Module httpModule = ModuleLayer.boot().findModule("java.net.http")
                .orElseThrow(() -> new IllegalStateException(
                        "java.net.http module is not present in the boot module layer"));

        byte[] bytes;
        try (InputStream in = httpModule.getResourceAsStream(TARGET_RESOURCE)) {
            if (in == null) {
                fatal("Cannot read JDK target class resource: " + TARGET_RESOURCE, null);
                return; // unreachable
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            fatal("Failed to read JDK target class resource: " + TARGET_RESOURCE, e);
            return; // unreachable
        }

        String problem = SSLFlowGuardTransformer.compatibilityProblem(bytes);
        if (problem != null) {
            fatal("Current JDK is not compatible with this guard: " + problem, null);
        }
    }

    private static Class<?> findLoadedTarget(Instrumentation instrumentation) {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (TARGET_BINARY_NAME.equals(type.getName())) {
                return type;
            }
        }
        return null;
    }

    static void fatal(String message, Throwable cause) {
        System.err.println("[SSLFlowGuard] FATAL: " + message);
        if (cause != null) {
            cause.printStackTrace(System.err);
        }
        throw new IllegalStateException("SSLFlowGuard installation failed: " + message, cause);
    }
}
