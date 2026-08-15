import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ForgotPasswordComponent } from './forgot-password.component';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * W10 — étape 1 du flux « mot de passe oublié ». Ce que ces tests épinglent :
 * le message de succès est le MÊME quelle que soit l'adresse (anti-énumération,
 * le serveur répond 200 inconditionnellement), et une panne réseau est dite
 * comme une panne — jamais comme une information sur l'existence du compte.
 */
describe('ForgotPasswordComponent — anti-énumération (W10)', () => {
  const auth = { requestPasswordReset: jasmine.createSpy('requestPasswordReset') };

  function build() {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ForgotPasswordComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    return TestBed.createComponent(ForgotPasswordComponent).componentInstance;
  }

  beforeEach(() => auth.requestPasswordReset.calls.reset());

  it('affiche le message de succès pour une adresse CONNUE', () => {
    auth.requestPasswordReset.and.returnValue(of(void 0));
    const cmp = build();
    cmp.form.setValue({ email: 'resp@epos.tn' });

    cmp.onSubmit();

    expect(auth.requestPasswordReset).toHaveBeenCalledWith('resp@epos.tn');
    expect(cmp.envoye()).toBeTrue();
  });

  it('affiche LE MÊME succès pour une adresse INCONNUE (le serveur répond 200)', () => {
    auth.requestPasswordReset.and.returnValue(of(void 0));
    const cmp = build();
    cmp.form.setValue({ email: 'personne@nulle-part.tn' });

    cmp.onSubmit();

    expect(cmp.envoye()).toBeTrue();
    expect(cmp.erreurReseau()).toBeFalse();
  });

  it("l'email est épuré des espaces avant l'envoi", () => {
    auth.requestPasswordReset.and.returnValue(of(void 0));
    const cmp = build();
    cmp.form.setValue({ email: '  resp@epos.tn  ' });
    // Validators.email tolère les espaces périphériques selon le navigateur ;
    // on force la validité pour tester le trim d'envoi seul.
    cmp.form.controls.email.setErrors(null);

    cmp.onSubmit();

    expect(auth.requestPasswordReset).toHaveBeenCalledWith('resp@epos.tn');
  });

  it('une PANNE est une panne — pas un verdict sur l’adresse', () => {
    auth.requestPasswordReset.and.returnValue(throwError(() => new Error('down')));
    const cmp = build();
    cmp.form.setValue({ email: 'resp@epos.tn' });

    cmp.onSubmit();

    expect(cmp.envoye()).toBeFalse();
    expect(cmp.erreurReseau()).toBeTrue();
  });

  it('email invalide → aucun appel serveur', () => {
    const cmp = build();
    cmp.form.setValue({ email: 'pas-un-email' });

    cmp.onSubmit();

    expect(auth.requestPasswordReset).not.toHaveBeenCalled();
  });
});
