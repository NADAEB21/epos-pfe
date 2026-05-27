import { CommonModule } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { AuthStore } from '../auth/auth.store';
import { RoleType } from '../auth/auth.models';

const ROLE_LABELS: Record<RoleType, string> = {
  SUPER_ADMIN: 'Super Admin',
  RESPONSABLE_MATIERE: 'Responsable Matière',
  EVALUATEUR: 'Évaluateur',
};

@Component({
  selector: 'app-topbar',
  standalone: true,
  imports: [CommonModule],
  template: `
    <header class="h-16 px-6 flex items-center justify-end bg-surface border-b border-gray-200 relative">
      <button
        type="button"
        (click)="toggleMenu()"
        class="flex items-center gap-3 group focus:outline-none"
      >
        <div class="text-right">
          <div class="text-sm font-medium text-gray-900">Dr. {{ displayName() }}</div>
          <div class="text-xs text-gray-500">{{ roleLabel() }}</div>
        </div>
        <div
          class="w-10 h-10 rounded-full bg-brand text-white flex items-center justify-center text-sm font-semibold group-hover:bg-brand-dark transition-colors"
        >
          {{ initials() }}
        </div>
      </button>

      @if (menuOpen()) {
        <div class="fixed inset-0 z-10" (click)="closeMenu()"></div>
        <div
          class="absolute right-6 top-14 z-20 w-56 rounded-lg bg-white shadow-lg border border-gray-200 py-1"
        >
          <button
            type="button"
            class="w-full text-left px-4 py-2 text-sm text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed"
            disabled
          >
            Mon profil
            <span class="block text-xs text-gray-400">À venir</span>
          </button>
          <div class="border-t border-gray-100 my-1"></div>
          <button
            type="button"
            (click)="logout()"
            class="w-full text-left px-4 py-2 text-sm text-status-danger hover:bg-red-50"
          >
            Déconnexion
          </button>
        </div>
      }
    </header>
  `,
})
export class TopbarComponent {
  private readonly auth = inject(AuthService);
  private readonly store = inject(AuthStore);
  private readonly router = inject(Router);

  readonly menuOpen = signal(false);
  readonly user = this.store.currentUser;

  readonly displayName = computed(() => {
    const u = this.user();
    if (!u) return '';
    const local = u.email.split('@')[0];
    return local
      .split(/[._-]/)
      .filter(Boolean)
      .map((p) => p[0].toUpperCase() + p.slice(1))
      .join(' ');
  });

  readonly initials = computed(() => {
    const name = this.displayName();
    if (!name) return '?';
    const parts = name.split(' ').filter(Boolean);
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  });

  readonly roleLabel = computed(() => {
    const u = this.user();
    if (!u || u.authorities.length === 0) return '';
    return ROLE_LABELS[u.authorities[0].role];
  });

  toggleMenu(): void {
    this.menuOpen.update((v) => !v);
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
