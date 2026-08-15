import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

type ErrorKind = 'credentials' | 'locked' | 'network' | null;

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  readonly submitting = signal(false);
  readonly errorKind = signal<ErrorKind>(null);
  /**
   * #294 — le serveur distingue désormais « désactivé » (l'administration seule
   * peut rouvrir) de « temporairement verrouillé » (et annonce le délai). Un
   * texte figé côté client redirait forcément faux dans un cas sur deux, alors
   * on affiche SON message — le seul qui connaisse l'état et la durée.
   */
  readonly lockedMessage = signal<string | null>(null);

  onSubmit(): void {
    if (this.form.invalid || this.submitting()) return;

    this.errorKind.set(null);
    this.lockedMessage.set(null);
    this.submitting.set(true);

    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => {
        this.submitting.set(false);
        // Land on '' and let landingRedirectGuard route by role
        // (Responsable → /accueil, Super-admin → /admin).
        this.router.navigateByUrl('/');
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        if (err.status === 401) this.errorKind.set('credentials');
        else if (err.status === 403) {
          this.errorKind.set('locked');
          this.lockedMessage.set(err.error?.message ?? null);
        }
        else this.errorKind.set('network');
      },
    });
  }
}
