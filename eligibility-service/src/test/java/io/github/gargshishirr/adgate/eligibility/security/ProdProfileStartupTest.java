package io.github.gargshishirr.adgate.eligibility.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.NestedExceptionUtils;

import io.github.gargshishirr.adgate.eligibility.EligibilityServiceApplication;

/** The prod profile starts only with strong keys, and explains a refusal without a stack trace. */
@ExtendWith(OutputCaptureExtension.class)
class ProdProfileStartupTest {

    /** Command-line arguments, so keys in the environment of whoever runs the tests cannot change the outcome. */
    private static final String[] QUIET = {
            "--adgate.warm-up=0s", "--adgate.decisions.publish=false", "--adgate.spend.discover-partitions=false",
            "--spring.main.banner-mode=off"};

    @Test
    void refusesToStartWithMissingOrShortKeys(CapturedOutput output) {
        assertThatThrownBy(() -> run("--adgate.security.admin-keys=", "--adgate.security.client-keys=too-short-key"))
                .satisfies(error -> assertThat(NestedExceptionUtils.getMostSpecificCause(error))
                        .isInstanceOf(InvalidApiKeysException.class));

        assertThat(output)
                .contains("APPLICATION FAILED TO START")
                .contains("ADGATE_ADMIN_KEY is not set")
                .contains("every key in ADGATE_CLIENT_KEY must be at least 32 characters")
                .contains("openssl rand -hex 32")
                .doesNotContain("too-short-key")
                .doesNotContain("AdGate made random keys");
    }

    @Test
    void startsWithStrongKeys() {
        try (ConfigurableApplicationContext context = run(
                "--adgate.security.admin-keys=" + "a".repeat(32), "--adgate.security.client-keys=" + "c".repeat(32))) {
            assertThat(context.getBean(ApiKeys.class).roleOf("a".repeat(32))).isEqualTo(ApiKeys.Role.ADMIN);
        }
    }

    private static ConfigurableApplicationContext run(String... keyArguments) {
        String[] arguments = new String[QUIET.length + keyArguments.length];
        System.arraycopy(QUIET, 0, arguments, 0, QUIET.length);
        System.arraycopy(keyArguments, 0, arguments, QUIET.length, keyArguments.length);
        return new SpringApplicationBuilder(EligibilityServiceApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .run(arguments);
    }
}
