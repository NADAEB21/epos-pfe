package tn.epos.auth_service.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.epos.auth_service.entity.RoleType;
import tn.epos.auth_service.entity.User;
import tn.epos.auth_service.entity.UserRole;
import tn.epos.auth_service.repository.UserRepository;
import tn.epos.auth_service.repository.UserRoleRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final Clock clock;

    /**
     * Called by Spring Security during form/basic auth and by the JWT filter
     * after token validation to reconstruct the SecurityContext.
     *
     * Authorities are built with the same format as JwtService.buildAuthorities()
     * so that @PreAuthorize expressions work identically whether the authority
     * came from a fresh DB load or was decoded from a JWT.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException(
                        "No user found with email: " + email));

        List<UserRole> roles = userRoleRepository.findByUserId(user.getId());
        Collection<GrantedAuthority> authorities = buildGrantedAuthorities(roles);

        return org.springframework.security.core.userdetails.User.builder()
                .username(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(authorities)
                // #294 : pour Spring Security, les deux états se valent — retrait
                // administratif durable ou verrou temporaire qui expire, la
                // personne ne peut pas entrer. Seul le message les distingue,
                // et il est construit dans AuthService
                .accountLocked(!user.getIsActive() || isTemporarilyLocked(user))
                .build();
    }

    /** #294 — un verrou temporaire encore en cours. */
    private boolean isTemporarilyLocked(User user) {
        return user.getLockedUntil() != null
                && LocalDateTime.now(clock).isBefore(user.getLockedUntil());
    }

    // -------------------------------------------------------------------------
    // Authority conversion
    // -------------------------------------------------------------------------

    /**
     * Mirrors JwtService.buildAuthorities() exactly:
     *   ROLE_RESPONSABLE_MATIERE:5  (scoped)
     *   ROLE_SUPER_ADMIN            (global)
     *   ROLE_EVALUATEUR             (global)
     */
    private Collection<GrantedAuthority> buildGrantedAuthorities(List<UserRole> roles) {
        return roles.stream()
                .map(this::toGrantedAuthority)
                .map(SimpleGrantedAuthority::new)
                .<GrantedAuthority>map(a -> a)
                .toList();
    }

    private String toGrantedAuthority(UserRole userRole) {
        String base = "ROLE_" + userRole.getRole().name();
        if (userRole.getRole() == RoleType.RESPONSABLE_MATIERE
                && userRole.getMatiereId() != null) {
            return base + ":" + userRole.getMatiereId();
        }
        return base;
    }
}
