package io.github.gargshishirr.adgate.eligibility.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

/** Writes RFC 9457 problem+json error responses from servlet filters, which run before Spring MVC. */
final class ProblemResponses {

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, ObjectMapper mapper, HttpStatus status, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ProblemDetail.forStatusAndDetail(status, detail));
    }
}
