package io.github.gargshishirr.adgate.eligibility.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param adminKeys         ADGATE_ADMIN_KEY: one key, or several separated by commas while rotating
 * @param clientKeys        ADGATE_CLIENT_KEY: one key, or several separated by commas while rotating
 * @param requireStrongKeys refuse to start unless both are set with keys of 32+ characters (on in the prod profile)
 */
@ConfigurationProperties("adgate.security")
public record ApiKeyProperties(String adminKeys, String clientKeys, boolean requireStrongKeys) {
}
