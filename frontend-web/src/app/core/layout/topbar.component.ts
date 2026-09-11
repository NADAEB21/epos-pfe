import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { AuthStore } from '../auth/auth.store';
import { RoleType } from '../auth/auth.models';
import { IconComponent } from '../../shared/ui/icon.component';
import { LayoutStore } from './layout.store';
import { PageTitleService } from './page-title.service';

const ROLE_LABELS: Record<RoleType, string> = {
  SUPER_ADMIN: 'Super Admin',
  RESPONSABLE_MATIERE: 'Responsable Matière',
  EVALUATEUR: 'Évaluateur',
};

@Component({
  selector: 'app-topbar',
  standalone: true,
  imports: [CommonModule, IconComponent],
  templateUrl: './topbar.component.html',
})
export class TopbarComponent {
  private readonly auth = inject(AuthService);
  private readonly store = inject(AuthStore);
  private readonly router = inject(Router);
  readonly layout = inject(LayoutStore);
  /** #405 — le titre de la page courante (data.title de la route). */
  readonly pageTitle = inject(PageTitleService).current;

  readonly menuOpen = signal(false);
  readonly user = this.store.currentUser;

  /**
   * #389 (R4) — « Prénom Nom » servis par GET /auth/me (même ordre que
   * `User.nomComplet` du mobile). Repli tant que le profil n'est pas arrivé :
   * la partie locale de l'e-mail — c'était l'UNIQUE source avant, et elle
   * affichait « aouina40rania » pour Rania Aouina.
   */
  readonly displayName = computed(() => {
    const u = this.user();
    if (!u) return '';
    if (u.prenom && u.nom) return `${u.prenom} ${u.nom}`;
    const local = u.email.split('@')[0];
    return local
      .split(/[._-]/)
      .filter(Boolean)
      .map((p) => p[0].toUpperCase() + p.slice(1))
      .join(' ');
  });

  /** Initiales prénom+nom (ordre du mobile `User.initiales` et du profil web). */
  readonly initials = computed(() => {
    const u = this.user();
    if (u?.prenom && u.nom) return (u.prenom[0] + u.nom[0]).toUpperCase();
    const name = this.displayName();
    if (!name) return '?';
    const parts = name.split(' ').filter(Boolean);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  });

  readonly roleLabel = computed(() => {
    const u = this.user();
    if (!u) return '';
    // Le rôle principal du serveur est déterministe (précédence) ; l'ordre de
    // authorities[] dans le JWT ne l'est pas.
    if (u.primaryRole) return ROLE_LABELS[u.primaryRole];
    if (u.authorities.length === 0) return '';
    return ROLE_LABELS[u.authorities[0].role];
  });

  toggleMenu(): void {
    this.menuOpen.update((v) => !v);
  }

  /** W1 — l'entrée « Mon profil » cesse d'être un bouton mort « À venir ». */
  goToProfile(): void {
    this.menuOpen.set(false);
    this.router.navigateByUrl('/parametres/profil');
  }

  closeMenu(): void {
    this.menuOpen.set(false);
  }

  logout(): void {
    this.menuOpen.set(false);
    this.auth.logout().subscribe({
      complete: () => this.router.navigateByUrl('/login'),
    });
  }
}
