package io.github.gargshishirr.adgate.eligibility.security;

import java.util.stream.Collectors;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Turns a refused key configuration into a short "what is wrong, what to do" report instead of a stack trace. */
class InvalidApiKeysFailureAnalyzer extends AbstractFailureAnalyzer<InvalidApiKeysException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, InvalidApiKeysException cause) {
        String description = "AdGate refused to start because its API keys are not safe:\n"
                + cause.problems().stream().map(problem -> "  - " + problem).collect(Collectors.joining("\n"));
        String action = """
                Set ADGATE_ADMIN_KEY and ADGATE_CLIENT_KEY to two different random values of at least \
                %d characters, for example with: openssl rand -hex 32
                While you rotate, either one may hold several keys separated by commas.""".formatted(ApiKeys.MIN_STRONG_KEY_LENGTH);
        return new FailureAnalysis(description, action, cause);
    }
}
