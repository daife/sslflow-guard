package dev.sslflowguard;

import java.util.Locale;

final class AgentOptions {
    final boolean debug;
    final boolean quiet;

    private AgentOptions(boolean debug, boolean quiet) {
        this.debug = debug;
        this.quiet = quiet;
    }

    static AgentOptions parse(String raw) {
        boolean debug = false;
        boolean quiet = false;

        if (raw != null && !raw.isBlank()) {
            for (String token : raw.split("[,;]")) {
                String option = token.trim().toLowerCase(Locale.ROOT);
                switch (option) {
                    case "", "production", "prod" -> { }
                    case "debug" -> debug = true;
                    case "quiet" -> quiet = true;
                    default -> throw new IllegalArgumentException(
                            "Unknown sslflow-guard agent option: '" + token.trim()
                                    + "'. Supported: production, debug, quiet");
                }
            }
        }

        return new AgentOptions(debug, quiet);
    }
}
