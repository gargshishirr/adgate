package io.github.gargshishirr.adgate.eligibility.security;

import java.util.List;

/** The API key configuration is not safe to start with. Messages describe the problem and never contain a key. */
public class InvalidApiKeysException extends RuntimeException {

    private final List<String> problems;

    public InvalidApiKeysException(List<String> problems) {
        super("Unsafe API key configuration: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
