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
  templateUrl: './topbar.component.html',
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
