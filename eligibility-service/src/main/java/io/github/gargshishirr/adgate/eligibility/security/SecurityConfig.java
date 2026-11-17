package io.github.gargshishirr.adgate.eligibility.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.gargshishirr.adgate.eligibility.config.AdgateProperties;

/**
 * Registers the API key and body size filters on the main server only. They run right after the HTTP metrics filter
 * ({@code HIGHEST_PRECEDENCE + 1}), so refused calls still show up in {@code http.server.requests}, and before any
 * filter that could read the body. (Spring's form filter, which would read PUT form bodies, is also switched off in
 * application.yml.)
 */
@Configuration
class SecurityConfig {

    static final int API_KEY_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    @Bean
    ApiKeys apiKeys(ApiKeyProperties properties) {
        return ApiKeys.create(properties);
    }

    @Bean
    FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(ApiKeys keys, ObjectMapper mapper) {
        FilterRegistrationBean<ApiKeyFilter> registration = new FilterRegistrationBean<>(new ApiKeyFilter(keys, mapper));
        registration.setOrder(API_KEY_FILTER_ORDER);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestSizeLimitFilter> requestSizeLimitFilter(AdgateProperties properties, ObjectMapper mapper) {
        FilterRegistrationBean<RequestSizeLimitFilter> registration = new FilterRegistrationBean<>(
                new RequestSizeLimitFilter(properties.maxRequestBody().toBytes(), mapper));
        registration.setOrder(API_KEY_FILTER_ORDER + 1);
        return registration;
    }
}
