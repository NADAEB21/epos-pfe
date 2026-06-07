import { Component, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Shown when an authenticated user has no web-console role (a pure EVALUATEUR).
 * The web app is the Responsable/Admin console; évaluateurs score on the
 * Flutter mobile app (ADR-0001). Rendered outside the app shell — no sidebar,
 * since this user has no navigation to offer.
 */
@Component({
  selector: 'app-acces-refuse',
  standalone: true,
  template: `
    <div class="min-h-screen flex items-center justify-center bg-gray-50 px-4">
      <div class="w-full max-w-md rounded-xl bg-white border border-gray-200 shadow-card p-8 text-center">
        <div
          class="w-12 h-12 mx-auto mb-4 rounded-full bg-status-warning/15 flex items-center justify-center"
        >
          <svg class="w-6 h-6 text-status-warning" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M12 9v3.75m0 3.75h.008M10.34 3.94l-7.5 13a1.5 1.5 0 001.3 2.25h15a1.5 1.5 0 001.3-2.25l-7.5-13a1.5 1.5 0 00-2.6 0z" />
          </svg>
        </div>
        <h1 class="text-xl font-semibold text-gray-900 mb-2">Accès non autorisé</h1>
        <p class="text-sm text-gray-600 mb-1">
          Cette console web est réservée aux responsables de matière et aux administrateurs.
        </p>
        <p class="text-sm text-gray-600 mb-6">
          En tant qu'évaluateur, utilisez l'application mobile EPOS pour vos notations.
        </p>
        <button
          type="button"
          (click)="logout()"
          [disabled]="loggingOut()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors disabled:opacity-60"
        >
          Se déconnecter
        </button>
      </div>
    </div>
  `,
})
export class AccesRefuseComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly loggingOut = signal(false);

  logout(): void {
    this.loggingOut.set(true);
    this.auth.logout().subscribe({
      complete: () => this.router.navigateByUrl('/login'),
    });
  }
}
