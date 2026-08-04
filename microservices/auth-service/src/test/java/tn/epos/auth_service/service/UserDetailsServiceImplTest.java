package tn.epos.auth_service.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * #294 — le chemin Spring Security, jusqu'ici sans test.
 *
 * <p>C'est lui qui décide si un compte « peut entrer », et depuis V2 la réponse
 * dépend de DEUX états qui ne se ressemblent plus : le retrait administratif
 * ({@code isActive}) et le verrou temporaire ({@code lockedUntil}). Le second
 * expire tout seul — sans horloge fixe, aucun test ne pourrait le prouver.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailsServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;

    @Spy private Clock clock = Clock.fixed(Instant.parse("2026-08-04T09:00:00Z"), ZoneId.of("UTC"));

    @InjectMocks private UserDetailsServiceImpl service;

    private User user(boolean active, LocalDateTime lockedUntil) {
        return User.builder()
                .id(1L).email("user@test.com").passwordHash("hashed-pw")
                .nom("Test").prenom("User")
                .isActive(active)
                .lockedUntil(lockedUntil)
                .build();
    }

    private void stubDirectory(User u) {
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(u));
        when(userRoleRepository.findByUserId(1L)).thenReturn(List.of(
                UserRole.builder().role(RoleType.EVALUATEUR).build()));
    }

    @Test
    void activeUser_isNotLocked() {
        stubDirectory(user(true, null));

        UserDetails details = service.loadUserByUsername("user@test.com");

        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.getAuthorities())
                .extracting("authority").containsExactly("ROLE_EVALUATEUR");
    }

    @Test
    void administrativelyDeactivatedUser_isLocked() {
        stubDirectory(user(false, null));

        assertThat(service.loadUserByUsername("user@test.com").isAccountNonLocked()).isFalse();
    }

    @Test
    void temporaryLockStillRunning_isLocked() {
        LocalDateTime future = LocalDateTime.now(clock).plusMinutes(5);
        stubDirectory(user(true, future));

        assertThat(service.loadUserByUsername("user@test.com").isAccountNonLocked()).isFalse();
    }

    @Test
    void expiredTemporaryLock_isNotLockedAnymore() {
        // Le cœur de #294 : le verrou s'ouvre SEUL, sans intervention.
        LocalDateTime past = LocalDateTime.now(clock).minusSeconds(1);
        stubDirectory(user(true, past));

        assertThat(service.loadUserByUsername("user@test.com").isAccountNonLocked()).isTrue();
    }

    @Test
    void unknownEmail_throws() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost@test.com"))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}
