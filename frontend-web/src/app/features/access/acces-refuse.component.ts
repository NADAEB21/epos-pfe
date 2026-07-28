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
  templateUrl: './acces-refuse.component.html',
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
