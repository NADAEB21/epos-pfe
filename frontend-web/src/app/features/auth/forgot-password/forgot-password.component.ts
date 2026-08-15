import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * W10 — « Mot de passe oublié », étape 1 (l'email). Miroir du flux mobile
 * (forgot_password_screen.dart, BF1.3) : le serveur répond TOUJOURS 200
 * (anti-énumération), donc l'écran affiche le même message que l'adresse
 * existe ou non — jamais « adresse inconnue ». L'étape 2 vit sur
 * /reset-password : le lien du mail y atterrit avec ?token=…, et le lien
 * « J'ai déjà un code » couvre le chemin copier-coller (comme sur mobile).
 */
@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './forgot-password.component.html',
})
export class ForgotPasswordComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);

  readonly form = this.fb.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
  });

  readonly submitting = signal(false);
  /** Vrai après un envoi accepté — l'écran bascule sur le message + la suite. */
  readonly envoye = signal(false);
  readonly erreurReseau = signal(false);

  onSubmit(): void {
    if (this.form.invalid || this.submitting()) return;

    this.erreurReseau.set(false);
    this.submitting.set(true);

    this.auth.requestPasswordReset(this.form.getRawValue().email.trim()).subscribe({
      next: () => {
        this.submitting.set(false);
        this.envoye.set(true);
      },
      // Seule une panne peut échouer ici (le 200 est inconditionnel côté
      // serveur) : on le dit comme tel, sans rien révéler sur l'adresse.
      error: () => {
        this.submitting.set(false);
        this.erreurReseau.set(true);
      },
    });
  }
}
