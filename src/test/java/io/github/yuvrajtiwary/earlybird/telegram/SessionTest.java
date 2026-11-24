package io.github.yuvrajtiwary.earlybird.telegram;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.yuvrajtiwary.earlybird.telegram.Session.Draft;
import io.github.yuvrajtiwary.earlybird.telegram.Session.State;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SessionTest {

    @Test
    void togglesAddAndRemove() {
        Draft d = Draft.empty().toggleRole("backend").toggleRole("mobile").toggleRole("backend");

        assertThat(d.roles()).containsExactly("mobile");
        assertThat(d.toggleLocation("blr").toggleLocation("rin").locations()).containsExactly("blr", "rin");
    }

    @Test
    void theDraftSurvivesAJsonRoundTrip() {
        var mapper = JsonMapper.builder().build();
        Draft d = Draft.empty().toggleRole("backend").toggleLocation("pune").withYears(1.5).withInternships(true);

        assertThat(mapper.readValue(mapper.writeValueAsString(d), Draft.class)).isEqualTo(d);
    }

    @Test
    void onlyOnboardingStatesCountAsOnboarding() {
        assertThat(State.ONBOARDING_RESUME.onboarding()).isTrue();
        assertThat(State.AWAITING_RESUME.onboarding()).isFalse();
        assertThat(State.IDLE.onboarding()).isFalse();
    }

    @Test
    void theWebhookSecretComparisonIsExact() {
        byte[] secret = "a".repeat(40).getBytes();

        assertThat(WebhookController.secretMatches(secret, "a".repeat(40))).isTrue();
        assertThat(WebhookController.secretMatches(secret, "a".repeat(39))).isFalse();
        assertThat(WebhookController.secretMatches(secret, "b" + "a".repeat(39))).isFalse();
        assertThat(WebhookController.secretMatches(secret, null)).isFalse();
    }
}
