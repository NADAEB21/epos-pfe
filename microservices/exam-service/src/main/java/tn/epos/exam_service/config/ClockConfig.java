package tn.epos.exam_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock} so time-dependent logic (exam pause/resume
 * accounting — ADR-0009) reads "now" from a bean instead of the static system
 * clock. Makes the zone explicit and lets tests inject a fixed clock for
 * deterministic timestamp/duration assertions.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
