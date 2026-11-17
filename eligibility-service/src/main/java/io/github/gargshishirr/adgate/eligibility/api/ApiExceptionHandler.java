package io.github.gargshishirr.adgate.eligibility.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import io.github.gargshishirr.adgate.eligibility.security.RequestSizeLimitFilter.BodyTooLargeException;

/**
 * Problem+json errors for the API. Details describe what was wrong in our own words and never echo request data:
 * validation errors list the field and the rule it broke, not the rejected value.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /** One field or parameter that broke a rule. */
    record InvalidField(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = ex.getBindingResult().getFieldErrors().stream().map(ApiExceptionHandler::invalidField).toList();
        return validationProblem(ex, errors, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        List<InvalidField> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors objectErrors) {
                objectErrors.getFieldErrors().stream().map(ApiExceptionHandler::invalidField).forEach(errors::add);
            } else {
                String parameter = result.getMethodParameter().getParameterName();
                for (MessageSourceResolvable error : result.getResolvableErrors()) {
                    errors.add(new InvalidField(parameter, error.getDefaultMessage()));
                }
            }
        }
        return validationProblem(ex, errors, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        if (causedBy(ex, BodyTooLargeException.class)) {
            return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                    "The request body is too large."), headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
        }
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status,
                "The request body is missing, is not valid JSON, or has a value of the wrong type."), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status,
                                                        WebRequest request) {
        String parameter = ex.getPropertyName();
        String detail = parameter == null ? "A request parameter has an invalid value."
                : "Parameter '" + parameter + "' has an invalid value.";
        return handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, detail), headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex, HttpHeaders headers,
                                                                     HttpStatusCode status, WebRequest request) {
        ex.getBody().setDetail("Send the request body as application/json.");
        return super.handleHttpMediaTypeNotSupported(ex, headers, status, request);
    }

    private ResponseEntity<Object> validationProblem(Exception ex, List<InvalidField> errors, HttpHeaders headers,
                                                     HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "The request is not valid.");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    private static InvalidField invalidField(FieldError error) {
        return new InvalidField(error.getField(), error.getDefaultMessage());
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
