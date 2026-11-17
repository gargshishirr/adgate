package io.github.gargshishirr.adgate.eligibility.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import io.github.gargshishirr.adgate.eligibility.security.ApiKeys.Role;

@ExtendWith(OutputCaptureExtension.class)
class ApiKeysTest {

    private static final String ADMIN = "a".repeat(32);
    private static final String CLIENT = "c".repeat(32);
    private static final String NEXT_CLIENT = "n".repeat(40);

    @Test
    void keysOnlyGrantTheirOwnRole() {
        ApiKeys keys = ApiKeys.create(new ApiKeyProperties(ADMIN, CLIENT, true));

        assertThat(keys.roleOf(ADMIN)).isEqualTo(Role.ADMIN);
        assertThat(keys.roleOf(CLIENT)).isEqualTo(Role.CLIENT);
        assertThat(keys.roleOf(CLIENT + "c")).isNull();
        assertThat(keys.roleOf(CLIENT.substring(1))).isNull();
        assertThat(keys.roleOf("")).isNull();
        assertThat(keys.roleOf(null)).isNull();
    }

    @Test
    void aCommaSeparatedListLetsOldAndNewKeysWorkDuringARotation() {
        ApiKeys keys = ApiKeys.create(new ApiKeyProperties(ADMIN, " " + CLIENT + " , " + NEXT_CLIENT + ",", true));

        assertThat(keys.roleOf(CLIENT)).isEqualTo(Role.CLIENT);
        assertThat(keys.roleOf(NEXT_CLIENT)).isEqualTo(Role.CLIENT);
        assertThat(keys.firstClientKey()).isEqualTo(CLIENT);
    }

    @Test
    void locallyAMissingKeyIsGeneratedAndLoggedOnce(CapturedOutput output) {
        ApiKeys keys = ApiKeys.create(new ApiKeyProperties(null, "", false));

        String generated = keys.firstClientKey();
        assertThat(generated).hasSize(64).matches("[0-9a-f]+");
        assertThat(keys.roleOf(generated)).isEqualTo(Role.CLIENT);
        assertThat(output).containsOnlyOnce("export ADGATE_ADMIN_KEY=").containsOnlyOnce(" ADGATE_CLIENT_KEY=" + generated);
        String adminKey = output.toString().replaceAll("(?s).*export ADGATE_ADMIN_KEY=([0-9a-f]{64}).*", "$1");
        assertThat(keys.roleOf(adminKey)).isEqualTo(Role.ADMIN);
        assertThat(adminKey).isNotEqualTo(generated);
    }

    @Test
    void locallyAShortKeyWorksButIsWarnedAbout(CapturedOutput output) {
        ApiKeys keys = ApiKeys.create(new ApiKeyProperties("dev-admin", "dev-client", false));

        assertThat(keys.roleOf("dev-admin")).isEqualTo(Role.ADMIN);
        assertThat(output).contains("ADGATE_ADMIN_KEY has a key shorter than 32 characters").doesNotContain("dev-admin");
    }

    @Test
    void strongModeRefusesMissingAndShortKeysAndNamesEveryProblem() {
        assertThatThrownBy(() -> ApiKeys.create(new ApiKeyProperties("", CLIENT + "," + "short", true)))
                .isInstanceOfSatisfying(InvalidApiKeysException.class, e -> assertThat(e.problems()).containsExactly(
                        "ADGATE_ADMIN_KEY is not set",
                        "every key in ADGATE_CLIENT_KEY must be at least 32 characters"))
                .hasMessageNotContaining("short");
    }

    @Test
    void theSameKeyCannotBeBothAdminAndClient() {
        assertThatThrownBy(() -> ApiKeys.create(new ApiKeyProperties(ADMIN, NEXT_CLIENT + "," + ADMIN, false)))
                .isInstanceOf(InvalidApiKeysException.class)
                .hasMessageContaining("admin and client keys must differ")
                .hasMessageNotContaining(ADMIN);
    }

    @Test
    void keysMustBeSendableInAHeader() {
        assertThatThrownBy(() -> ApiKeys.create(new ApiKeyProperties(ADMIN, "c".repeat(20) + " " + "c".repeat(20), false)))
                .isInstanceOf(InvalidApiKeysException.class)
                .hasMessageContaining("printable ASCII");
    }
}
