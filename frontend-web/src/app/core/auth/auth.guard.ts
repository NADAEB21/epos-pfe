import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
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

  // Send already-authenticated users to '' — landingRedirectGuard then routes
  // them to the right home for their role. Avoids hardcoding /accueil here.
  if (!store.isAuthenticated()) return true;

  router.navigate(['/']);
  return false;
};

/**
 * Gates the whole web console. The app is the Responsable workspace + Admin
 * oversight; a pure EVALUATEUR (mobile-only per ADR-0001) is sent to an
 * explicit access-refused page instead of a broken, matiere-scoped Accueil.
 */
export const webAccessGuard: CanActivateFn = (): boolean | UrlTree => {
  const store = inject(AuthStore);
  const router = inject(Router);

  if (store.canAccessWeb()) return true;

  return router.parseUrl('/acces-refuse');
};

/**
 * Role-aware landing for '' — the single place that decides a user's home:
 * Responsables (incl. compound users) get the matiere workspace Accueil,
 * pure Super-admins get the Administration overview.
 */
export const landingRedirectGuard: CanActivateFn = (): UrlTree => {
  const store = inject(AuthStore);
  const router = inject(Router);

  if (store.isResponsable()) return router.parseUrl('/accueil');
  if (store.isSuperAdmin()) return router.parseUrl('/admin');
  return router.parseUrl('/acces-refuse');
};

/**
 * Guards /accueil + the matiere workspace — Responsable scope only. A pure
 * Super-admin who lands here (e.g. via a bookmarked URL) is redirected to
 * their own Administration overview rather than shown a matiere triage they
 * do not own.
 */
export const responsableGuard: CanActivateFn = (): boolean | UrlTree => {
  const store = inject(AuthStore);
  const router = inject(Router);

  if (store.isResponsable()) return true;
  if (store.isSuperAdmin()) return router.parseUrl('/admin');
  return router.parseUrl('/acces-refuse');
};

/** Gates the Administration zone — SUPER_ADMIN only; others bounce to '' (re-routed by role). */
export const superAdminGuard: CanActivateFn = (): boolean | UrlTree => {
  const store = inject(AuthStore);
  const router = inject(Router);

  if (store.isSuperAdmin()) return true;

  return router.parseUrl('/');
};
