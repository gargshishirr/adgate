package io.github.gargshishirr.adgate.eligibility.domain;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.github.gargshishirr.adgate.common.RedisKeys;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * An id that is safe to use in Redis keys, Kafka keys, URLs and logs: 1 to 128 letters, digits, '.', '_' or '-',
 * starting with a letter or digit. Same rule as {@link RedisKeys#isSafeId}.
 */
@NotNull
@Pattern(regexp = RedisKeys.SAFE_ID_PATTERN)
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Documented
@Retention(RUNTIME)
@Target({FIELD, PARAMETER, TYPE_USE})
public @interface SafeId {

    String message() default "must be 1 to 128 letters, digits, '.', '_' or '-', starting with a letter or digit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
