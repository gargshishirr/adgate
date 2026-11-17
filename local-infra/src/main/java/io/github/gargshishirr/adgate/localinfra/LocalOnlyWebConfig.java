package io.github.gargshishirr.adgate.localinfra;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.web.server.ConfigurableWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Keeps the chaos switch reachable only from this machine, and only by tools such as curl. */
@Configuration
class LocalOnlyWebConfig {

    @Bean
    LoopbackOnly loopbackOnly() {
        return new LoopbackOnly();
    }

    @Bean
    FilterRegistrationBean<LocalCallersOnlyFilter> localCallersOnlyFilter() {
        FilterRegistrationBean<LocalCallersOnlyFilter> registration = new FilterRegistrationBean<>(new LocalCallersOnlyFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Runs after Spring Boot's own customizers, so even server.address=0.0.0.0 cannot widen the chaos switch. */
    static class LoopbackOnly implements WebServerFactoryCustomizer<ConfigurableWebServerFactory>, Ordered {

        @Override
        public void customize(ConfigurableWebServerFactory factory) {
            factory.setAddress(InetAddress.getLoopbackAddress());
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /**
     * Refuses what a web page could send on someone's behalf: any request with an Origin header (a page calling
     * across sites) and any Host that is not a loopback name (DNS rebinding).
     */
    static class LocalCallersOnlyFilter extends OncePerRequestFilter {

        private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String host = request.getServerName() == null ? "" : request.getServerName().toLowerCase(Locale.ROOT);
            if (request.getHeader(HttpHeaders.ORIGIN) != null || !LOOPBACK_HOSTS.contains(host)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"error\":\"The chaos switch only takes local calls, for example from curl.\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
