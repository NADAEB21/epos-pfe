package tn.epos.scoring_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * A single injectable {@link Clock} so time-dependent logic reads "now" from a
 * bean instead of the static system clock. Mirrors exam-service's
 * {@code ClockConfig} (ADR-0010) so both services share one zone policy, and
 * lets tests inject a fixed/spy clock for deterministic assertions.
 *
 * <p><b>Zone policy (ADR-0010, option A).</b> Pinned to an explicit application
 * zone ({@code app.timezone}, default {@code Africa/Tunis}) instead of
 * {@link Clock#systemDefaultZone()}. The container OS runs UTC; the exam
 * schedule ({@code Rotation.debutCreneau}) and the machine-stamped pause moments
 * ({@code paused_at}) must be compared in one declared zone, or the live board
 * mis-reads the current créneau (see ADR-0010 gap 2).
 *
 * <p><b>Why scoring-service needs it now (ADR-0012 §0).</b>
 * {@code EvaluateurDashboardService} derives the évaluateur's live session
 * status from "now" vs {@code debutCreneau}. Reconciling that with the exam's
 * pause state (ADR-0009 effective time) requires "now" and {@code paused_at} to
 * live in the same zone — the same fix exam-service already adopted.
 *
 * <p>Override per-deployment with {@code APP_TIMEZONE}. Single faculty zone
 * assumed — see ADR-0010 "Deferred".
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.timezone:Africa/Tunis}") String zoneId) {
        return Clock.system(ZoneId.of(zoneId));
    }
}
