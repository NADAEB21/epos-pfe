package tn.epos.scoring_service.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClockConfig")
class ClockConfigTest {

    private final ClockConfig config = new ClockConfig();

    @Test
    @DisplayName("Le bean Clock est pinné sur la zone fournie (ADR-0010)")
    void clock_pinnedToConfiguredZone() {
        Clock clock = config.clock("Africa/Tunis");

        assertThat(clock).isNotNull();
        assertThat(clock.getZone()).isEqualTo(ZoneId.of("Africa/Tunis"));
    }

    @Test
    @DisplayName("Override de zone respecté (ex. dev host)")
    void clock_honoursOverrideZone() {
        assertThat(config.clock("Europe/Paris").getZone())
                .isEqualTo(ZoneId.of("Europe/Paris"));
    }
}
