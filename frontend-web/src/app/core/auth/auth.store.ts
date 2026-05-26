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
