import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { LoginComponent } from './login.component';
import { AuthService } from '../../../core/auth/auth.service';

/**
 * #294 — l'écran de connexion ne doit plus inventer la raison du refus.
 *
 * <p>Il affichait un texte figé : « Compte verrouillé après 3 tentatives.
 * Contactez votre responsable. » Depuis V2 le serveur distingue deux états aux
 * remèdes opposés — un compte RETIRÉ (l'administration seule rouvre) et un
 * verrou TEMPORAIRE qui annonce son délai. Un texte figé redisait forcément
 * faux dans un cas sur deux ; c'est le message du serveur qui fait foi.
 */
describe('LoginComponent — le motif du refus vient du serveur (#294)', () => {
  const auth = { login: jasmine.createSpy('login') };

  function build() {
    TestBed.configureTestingModule({
      imports: [LoginComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    const fixture = TestBed.createComponent(LoginComponent);
    const cmp = fixture.componentInstance;
    cmp.form.setValue({ email: 'user@epos.tn', password: 'Password1' });
    return cmp;
  }

  beforeEach(() => {
    TestBed.resetTestingModule();
    auth.login.calls.reset();
  });

  function refuse(status: number, message?: string) {
    auth.login.and.returnValue(
      throwError(() => new HttpErrorResponse({ status, error: message ? { message } : null })),
    );
  }

  it('403 verrou temporaire : affiche le délai annoncé par le serveur', () => {
    const cmp = build();
    refuse(403, 'Compte temporairement verrouillé après 3 tentatives. Réessayez dans 2 minute(s).');

    cmp.onSubmit();

    expect(cmp.errorKind()).toBe('locked');
    expect(cmp.lockedMessage()).toContain('2 minute(s)');
  });

  it('403 compte retiré : affiche le motif administratif, pas un délai', () => {
    const cmp = build();
    refuse(403, "Compte désactivé. Contactez l'administration de la faculté.");

    cmp.onSubmit();

    expect(cmp.lockedMessage()).toContain('administration');
    expect(cmp.lockedMessage()).not.toContain('minute');
  });

  it('403 sans message exploitable : le repli reste affichable', () => {
    const cmp = build();
    refuse(403);

    cmp.onSubmit();

    expect(cmp.errorKind()).toBe('locked');
    expect(cmp.lockedMessage()).toBeNull(); // le gabarit fournit le texte de repli
  });

  it('401 reste « identifiants » et ne porte aucun message de verrou', () => {
    const cmp = build();
    refuse(401, 'Invalid email or password');

    cmp.onSubmit();

    expect(cmp.errorKind()).toBe('credentials');
    expect(cmp.lockedMessage()).toBeNull();
  });

  it('un nouvel essai efface le message précédent', () => {
    const cmp = build();
    refuse(403, 'Compte temporairement verrouillé. Réessayez dans 4 minute(s).');
    cmp.onSubmit();
    expect(cmp.lockedMessage()).not.toBeNull();

    auth.login.and.returnValue(of({ accessToken: 'a', refreshToken: 'r' }));
    cmp.onSubmit();

    expect(cmp.lockedMessage()).toBeNull();
    expect(cmp.errorKind()).toBeNull();
  });
});
