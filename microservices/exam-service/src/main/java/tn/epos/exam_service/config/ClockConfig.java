package tn.epos.exam_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * A single injectable {@link Clock} so time-dependent logic (exam pause/resume
 * accounting — ADR-0009 ; launch instant — ADR-0010) reads "now" from a bean
 * instead of the static system clock. Makes the zone explicit and lets tests
 * inject a fixed clock for deterministic timestamp/duration assertions.
 *
 * <p><b>Zone policy (ADR-0010, option A).</b> The clock is pinned to an
 * explicit application zone ({@code app.timezone}, default {@code Africa/Tunis})
 * instead of {@link Clock#systemDefaultZone()}. The container OS runs UTC; the
 * exam schedule ({@code heureDebut}/{@code dateExamen}/{@code Rotation.debutCreneau})
 * is the user-entered local wall-clock. Stamping machine moments
 * ({@code launched_at}, {@code paused_at}) via {@code LocalDateTime.now(clock)}
 * with a UTC clock put them hours away from the schedule they are compared
 * against. Pinning the clock zone restores the invariant that every value in the
 * effective-time formula lives in one declared zone.
 *
 * <p>Override per-deployment with {@code APP_TIMEZONE} (e.g. to match a dev
 * host's zone). Assumes a single faculty zone — see ADR-0010 "Deferred".
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.timezone:Africa/Tunis}") String zoneId) {
        return Clock.system(ZoneId.of(zoneId));
    }
}
