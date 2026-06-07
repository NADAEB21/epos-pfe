import { Injectable, computed, signal } from '@angular/core';
import { CurrentUser, RoleType } from './auth.models';

@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly _currentUser = signal<CurrentUser | null>(null);

  readonly currentUser = this._currentUser.asReadonly();
  readonly isAuthenticated = computed(() => this._currentUser() !== null);

  readonly responsableMatiereIds = computed<number[]>(() => {
    const user = this._currentUser();
    if (!user) return [];
    return user.authorities
      .filter((a) => a.role === 'RESPONSABLE_MATIERE' && a.matiereId !== null)
      .map((a) => a.matiereId as number);
  });

  // Persona signals — the single source of truth for nav visibility, route
  // guards, and the post-login landing decision. A compound user (e.g.
  // SUPER_ADMIN + RESPONSABLE_MATIERE) reads true on both.
  readonly isSuperAdmin = computed(() => this.hasGlobalRole('SUPER_ADMIN'));
  readonly isResponsable = computed(() => this.responsableMatiereIds().length > 0);
  // The web console is for the Responsable workspace + Admin oversight only.
  // Évaluateurs work in the Flutter mobile app (ADR-0001), so a pure
  // EVALUATEUR has no place here and is bounced to /acces-refuse.
  readonly canAccessWeb = computed(() => this.isSuperAdmin() || this.isResponsable());

  hasGlobalRole(role: RoleType): boolean {
    const user = this._currentUser();
    return user?.authorities.some((a) => a.role === role && a.matiereId === null) ?? false;
  }

  setUser(user: CurrentUser | null): void {
    this._currentUser.set(user);
  }

  clear(): void {
    this._currentUser.set(null);
  }
}
