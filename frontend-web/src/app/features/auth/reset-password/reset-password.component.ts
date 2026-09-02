import { Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import {
  AbstractControl,
  FormBuilder,
  ReactiveFormsModule,
  ValidationErrors,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * W10 — « Mot de passe oublié », étape 2 : consommer le code et poser le
 * nouveau mot de passe. Deux chemins d'arrivée :
 *  - le lien du mail (SpringMailEmailService) atterrit ici avec ?token=… —
 *    le champ code est alors pré-rempli et masqué ;
 *  - le chemin copier-coller (comme sur mobile, pas de deep-linking) : sans
 *    query param, le champ code est visible et saisissable.
 *
 * La politique de mot de passe reflète le DTO serveur (min 8, 1 majuscule,
 * 1 chiffre — PasswordResetConfirmDto) : le refus arrive AVANT l'appel, mais
 * le serveur reste l'autorité et son message s'affiche tel quel (code
 * invalide / expiré / déjà utilisé).
 */
@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './reset-password.component.html',
})
export class ResetPasswordComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly route = inject(ActivatedRoute);

  /** Le code venu de l'URL (lien du mail), ou null → saisie manuelle. */
  readonly tokenDepuisLien = signal<string | null>(null);

  /**
   * #389 — `?bienvenue=1` : la personne arrive par le lien d'INVITATION (compte
   * tout juste créé), pas par un « mot de passe oublié ». Même formulaire, même
   * appel serveur ; seule la lecture change (« Bienvenue » plutôt que
   * « Réinitialiser »).
   */
  readonly bienvenue = signal(false);

  readonly form = this.fb.nonNullable.group(
    {
      token: ['', [Validators.required]],
      newPassword: [
        '',
        // Miroir de PasswordResetConfirmDto : min 8, ≥1 majuscule, ≥1 chiffre.
        [Validators.required, Validators.minLength(8), Validators.pattern(/^(?=.*[A-Z])(?=.*\d).+$/)],
      ],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: [memesMotsDePasse] },
  );

  readonly submitting = signal(false);
  readonly reussi = signal(false);
  /** Message serveur (code invalide/expiré/utilisé), affiché tel quel. */
  readonly erreurServeur = signal<string | null>(null);
  readonly erreurReseau = signal(false);

  constructor() {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (token) {
      this.tokenDepuisLien.set(token);
      this.form.controls.token.setValue(token);
    }
    this.bienvenue.set(this.route.snapshot.queryParamMap.get('bienvenue') === '1');
  }

  onSubmit(): void {
    if (this.form.invalid || this.submitting()) return;

    const raw = this.form.getRawValue();
    this.erreurServeur.set(null);
    this.erreurReseau.set(false);
    this.submitting.set(true);

    this.auth.confirmPasswordReset(raw.token.trim(), raw.newPassword).subscribe({
      next: () => {
        this.submitting.set(false);
        this.reussi.set(true);
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        if (err.status === 0 || err.status >= 500) {
          this.erreurReseau.set(true);
          return;
        }
        // 400/401 : le serveur sait POURQUOI (invalide, expiré, déjà utilisé,
        // politique) — son message prime sur tout générique.
        const message = err.error?.message;
        this.erreurServeur.set(
          typeof message === 'string' && message
            ? message
            : 'Code invalide ou expiré. Refaites une demande de réinitialisation.',
        );
      },
    });
  }
}

/** Les deux saisies doivent coïncider — l'erreur vit sur le groupe. */
function memesMotsDePasse(group: AbstractControl): ValidationErrors | null {
  const a = group.get('newPassword')?.value;
  const b = group.get('confirmPassword')?.value;
  return a && b && a !== b ? { motsDePasseDifferents: true } : null;
}
