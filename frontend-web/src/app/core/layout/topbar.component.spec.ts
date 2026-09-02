import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TopbarComponent } from './topbar.component';
import { AuthService } from '../auth/auth.service';
import { AuthStore } from '../auth/auth.store';
import { CurrentUser } from '../auth/auth.models';

/**
 * #389 (R4) — l'en-tête affiche la PERSONNE, pas son adresse.
 *
 * <p>Avant : le nom était dérivé de la partie locale de l'e-mail — « Dr.
 * Aouina40rania », avatar « AO » pour Rania Aouina (aouina40rania@…). Le
 * profil serveur (GET /auth/me) porte nom et prénom ; le repli e-mail ne sert
 * que tant qu'il n'est pas arrivé.
 */
describe('TopbarComponent — le nom vient du profil, pas de l\'e-mail (#389)', () => {
  const auth = { logout: jasmine.createSpy('logout') };

  function build(user: CurrentUser) {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [TopbarComponent],
      providers: [provideRouter([]), { provide: AuthService, useValue: auth }],
    });
    TestBed.inject(AuthStore).setUser(user);
    const fixture = TestBed.createComponent(TopbarComponent);
    fixture.detectChanges();
    return { fixture, cmp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  const base: CurrentUser = {
    email: 'aouina40rania@epos.tn',
    userId: 12,
    authorities: [{ role: 'EVALUATEUR', matiereId: null }, { role: 'RESPONSABLE_MATIERE', matiereId: 1 }],
    accessTokenExpiresAt: new Date(Date.now() + 3_600_000),
  };

  it('profil connu : « Prénom Nom », initiales prénom+nom, rôle principal du serveur', () => {
    const { el, cmp } = build({ ...base, prenom: 'Rania', nom: 'Aouina', primaryRole: 'RESPONSABLE_MATIERE' });

    expect(el.textContent).toContain('Dr. Rania Aouina');
    expect(el.textContent).not.toContain('aouina40rania');
    expect(cmp.initials()).toBe('RA');
    expect(cmp.roleLabel()).toBe('Responsable Matière');
  });

  it('profil pas encore arrivé : repli sur l\'e-mail, jamais un écran vide', () => {
    const { el, cmp } = build(base);

    expect(cmp.displayName()).toBe('Aouina40rania');
    expect(el.textContent).toContain('Dr. Aouina40rania');
    expect(cmp.initials()).toBe('AO');
    // sans rôle principal servi, l'ancien comportement (authorities[0]) est conservé
    expect(cmp.roleLabel()).toBe('Évaluateur');
  });

  it('les initiales suivent l\'ordre prénom puis nom, comme le mobile', () => {
    const { cmp } = build({ ...base, prenom: 'Mohamed Ali', nom: 'Ben Salah' });
    expect(cmp.displayName()).toBe('Mohamed Ali Ben Salah');
    expect(cmp.initials()).toBe('MB');
  });
});
