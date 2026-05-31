import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStore } from './auth.store';

export const authGuard: CanActivateFn = () => {
  const store = inject(AuthStore);
  const router = inject(Router);

  if (store.isAuthenticated()) return true;

  router.navigate(['/login']);
  return false;
};

export const guestGuard: CanActivateFn = () => {
  const store = inject(AuthStore);
  const router = inject(Router);

  if (!store.isAuthenticated()) return true;

  router.navigate(['/accueil']);
  return false;
};

/** Gates the Administration zone — SUPER_ADMIN only; others bounce to Accueil. */
export const superAdminGuard: CanActivateFn = () => {
  const store = inject(AuthStore);
  const router = inject(Router);

  if (store.hasGlobalRole('SUPER_ADMIN')) return true;

  router.navigate(['/accueil']);
  return false;
};
