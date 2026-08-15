import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ResetPasswordComponent } from './reset-password.component';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * W10 — étape 2 : consommer le code, poser le nouveau mot de passe. Ce que ces
 * tests épinglent : le token du lien mail (?token=…) pré-remplit le champ, le
 * chemin copier-coller reste ouvert sans query param, la politique serveur
 * (min 8, majuscule, chiffre) et la concordance bloquent AVANT l'appel, et le
 * refus serveur (invalide/expiré/déjà utilisé) s'affiche mot pour mot.
 */
describe('ResetPasswordComponent — consommer le code (W10)', () => {
  const auth = { confirmPasswordReset: jasmine.createSpy('confirmPasswordReset') };

  function build(tokenInUrl: string | null) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ResetPasswordComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: auth },
        {
          provide: ActivatedRoute,
          useValue: {
            snapshot: {
              queryParamMap: convertToParamMap(tokenInUrl ? { token: tokenInUrl } : {}),
            },
          },
        },
      ],
    });
    return TestBed.createComponent(ResetPasswordComponent).componentInstance;
  }

  beforeEach(() => auth.confirmPasswordReset.calls.reset());

  it('le token du lien mail pré-remplit le formulaire', () => {
    const cmp = build('abc123');

    expect(cmp.tokenDepuisLien()).toBe('abc123');
    expect(cmp.form.controls.token.value).toBe('abc123');
  });

  it('sans query param : saisie manuelle du code (chemin copier-coller mobile)', () => {
    const cmp = build(null);

    expect(cmp.tokenDepuisLien()).toBeNull();
    expect(cmp.form.controls.token.value).toBe('');
  });

  it('chemin nominal : confirme puis affiche le panneau de succès', () => {
    auth.confirmPasswordReset.and.returnValue(of(void 0));
    const cmp = build('abc123');
    cmp.form.patchValue({ newPassword: 'Nouveau1234', confirmPassword: 'Nouveau1234' });

    cmp.onSubmit();

    expect(auth.confirmPasswordReset).toHaveBeenCalledWith('abc123', 'Nouveau1234');
    expect(cmp.reussi()).toBeTrue();
  });

  it('politique serveur reflétée : « nouveaumdp1 » (sans majuscule) bloque AVANT l’appel', () => {
    const cmp = build('abc123');
    cmp.form.patchValue({ newPassword: 'nouveaumdp1', confirmPassword: 'nouveaumdp1' });

    cmp.onSubmit();

    expect(cmp.form.controls.newPassword.invalid).toBeTrue();
    expect(auth.confirmPasswordReset).not.toHaveBeenCalled();
  });

  it('saisies différentes → erreur de groupe, aucun appel', () => {
    const cmp = build('abc123');
    cmp.form.patchValue({ newPassword: 'Nouveau1234', confirmPassword: 'Nouveau5678' });

    cmp.onSubmit();

    expect(cmp.form.hasError('motsDePasseDifferents')).toBeTrue();
    expect(auth.confirmPasswordReset).not.toHaveBeenCalled();
  });

  it('le refus serveur (code expiré/utilisé) s’affiche MOT POUR MOT', () => {
    const refus = 'Reset token is invalid or has expired';
    auth.confirmPasswordReset.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 401, error: { message: refus } })),
    );
    const cmp = build('perime');
    cmp.form.patchValue({ newPassword: 'Nouveau1234', confirmPassword: 'Nouveau1234' });

    cmp.onSubmit();

    expect(cmp.reussi()).toBeFalse();
    expect(cmp.erreurServeur()).toBe(refus);
  });

  it('une panne (status 0) est dite comme une panne, pas comme un code invalide', () => {
    auth.confirmPasswordReset.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 0 })),
    );
    const cmp = build('abc123');
    cmp.form.patchValue({ newPassword: 'Nouveau1234', confirmPassword: 'Nouveau1234' });

    cmp.onSubmit();

    expect(cmp.erreurReseau()).toBeTrue();
    expect(cmp.erreurServeur()).toBeNull();
  });
});
