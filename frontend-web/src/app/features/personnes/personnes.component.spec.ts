import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { PersonnesComponent } from './personnes.component';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { AuthStore } from '../../core/auth/auth.store';
import { UserResponse } from '../../core/api/models';

/**
 * #289 — retirer l'accès est un acte lourd : ces tests épinglent ce qui le rend
 * défendable — le motif obligatoire, la provenance lisible, et la réversibilité.
 *
 * <p>Ils couvrent aussi la portée de l'écran (#259) : un même composant sert
 * trois annuaires, et la mauvaise portée montrerait à un responsable des
 * comptes qui ne le regardent pas.
 */
describe('PersonnesComponent — retrait d’accès et portées', () => {
  const api = {
    listUsers: jasmine.createSpy('listUsers'),
    listMatieres: jasmine.createSpy('listMatieres'),
    deactivateUser: jasmine.createSpy('deactivateUser'),
    reactivateUser: jasmine.createSpy('reactivateUser'),
    createUser: jasmine.createSpy('createUser'),
    addRoles: jasmine.createSpy('addRoles'),
  };

  const user = (over: Partial<UserResponse>): UserResponse => ({
    id: 1,
    email: 'x@epos.tn',
    nom: 'Nom',
    prenom: 'Prenom',
    isActive: true,
    createdAt: '2026-08-01T10:00:00',
    roles: [{ role: 'EVALUATEUR', matiereId: null }],
    ...over,
  });

  const ADMIN = user({ id: 9, nom: 'Ben Ali', prenom: 'Aymen', email: 'admin@epos.tn' });

  function build(scope: 'evaluateurs' | 'co-responsables' | 'admin', users: UserResponse[]) {
    api.listUsers.and.returnValue(of(users));
    api.listMatieres.and.returnValue(of([{ id: 1, code: 'CT', libelle: 'Chimie thérapeutique' }]));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [PersonnesComponent],
      providers: [
        provideRouter([]),
        { provide: DirectoryApiService, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { data: { scope } } } },
      ],
    });
    const store = TestBed.inject(AuthStore);
    store.setUser({
      email: 'admin@epos.tn',
      userId: 9,
      authorities: [{ role: 'SUPER_ADMIN', matiereId: null }],
      accessTokenExpiresAt: new Date(Date.now() + 3_600_000),
    });
    return TestBed.createComponent(PersonnesComponent).componentInstance;
  }

  beforeEach(() => {
    api.deactivateUser.calls.reset();
    api.reactivateUser.calls.reset();
  });

  describe('le motif est une condition, pas une décoration', () => {
    it('refuse le retrait sans motif, SANS appeler le serveur', () => {
      const cmp = build('admin', [ADMIN, user({ id: 1 })]);
      cmp.askDeactivate(user({ id: 1 }));

      cmp.confirmDeactivate();

      expect(api.deactivateUser).not.toHaveBeenCalled();
      expect(cmp.deactivateError()).toContain('obligatoire');
    });

    it('transmet le motif quand il est renseigné', () => {
      api.deactivateUser.and.returnValue(of(undefined));
      const cmp = build('admin', [ADMIN, user({ id: 1 })]);
      cmp.askDeactivate(user({ id: 1 }));
      cmp.motifRetrait.set('  Depart de la faculte  ');

      cmp.confirmDeactivate();

      // trim : un motif fait d'espaces n'en est pas un
      expect(api.deactivateUser).toHaveBeenCalledWith(1, 'Depart de la faculte');
    });

    it('affiche mot pour mot le refus du serveur (soi-même, dernier admin)', () => {
      api.deactivateUser.and.returnValue(
        throwError(() => new HttpErrorResponse({
          status: 403,
          error: { message: 'Ce compte est le dernier administrateur actif de la plateforme.' },
        })),
      );
      const cmp = build('admin', [ADMIN, user({ id: 1 })]);
      cmp.askDeactivate(user({ id: 1 }));
      cmp.motifRetrait.set('menage');

      cmp.confirmDeactivate();

      expect(cmp.deactivateError()).toContain('dernier administrateur');
    });
  });

  describe('la provenance répond à « pourquoi ce compte est-il fermé ? »', () => {
    it('nomme la date, l’auteur et le motif', () => {
      const cmp = build('admin', [ADMIN]);
      const retire = user({
        id: 1,
        isActive: false,
        deactivatedAt: '2026-08-04T18:33:06',
        deactivatedBy: 9,
        deactivationMotif: 'Depart de la faculte',
      });

      const label = cmp.retraitLabel(retire);

      expect(label).toContain('04/08/2026');
      expect(label).toContain('Aymen Ben Ali');
      expect(label).toContain('Depart de la faculte');
    });

    it('reste honnête sur les comptes fermés AVANT la traçabilité', () => {
      const cmp = build('admin', [ADMIN]);

      const label = cmp.retraitLabel(user({ id: 1, isActive: false }));

      expect(label).toContain('motif inconnu');
    });
  });

  describe('réversibilité', () => {
    it('rouvre un compte avec son motif', () => {
      api.reactivateUser.and.returnValue(of(undefined));
      const cmp = build('admin', [ADMIN, user({ id: 1, isActive: false })]);
      cmp.askReactivate(user({ id: 1, isActive: false }));
      cmp.motifRetrait.set('Homonyme, retrait par erreur');

      cmp.confirmReactivate();

      expect(api.reactivateUser).toHaveBeenCalledWith(1, 'Homonyme, retrait par erreur');
    });

    it('Échap referme le panneau ouvert', () => {
      const cmp = build('admin', [ADMIN, user({ id: 1 })]);
      cmp.askDeactivate(user({ id: 1 }));
      expect(cmp.confirmingDeactivation()).not.toBeNull();

      cmp.onEscape();

      expect(cmp.confirmingDeactivation()).toBeNull();
    });
  });

  describe('portées (#259) — chacun voit ce qui le regarde', () => {
    it('« évaluateurs » ne liste que les évaluateurs', () => {
      const cmp = build('evaluateurs', [
        user({ id: 1, nom: 'Eval', roles: [{ role: 'EVALUATEUR', matiereId: null }] }),
        user({ id: 2, nom: 'Admin', roles: [{ role: 'SUPER_ADMIN', matiereId: null }] }),
      ]);

      expect(cmp.rows().map((u) => u.nom)).toEqual(['Eval']);
    });

    it('« admin » liste tout le monde, désactivés compris', () => {
      const cmp = build('admin', [
        user({ id: 1, nom: 'Actif' }),
        user({ id: 2, nom: 'Retire', isActive: false }),
      ]);

      expect(cmp.rows().length).toBe(2);
    });
  });
});
