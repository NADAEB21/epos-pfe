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
    resendInvitation: jasmine.createSpy('resendInvitation'),
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
    api.listMatieres.and.returnValue(
      of([{ id: 1, code: 'CT', libelle: 'Chimie thérapeutique', active: true }]),
    );
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

  describe('#389 — la création invite, elle ne fabrique plus de mot de passe', () => {
    beforeEach(() => {
      api.createUser.calls.reset();
      api.resendInvitation.calls.reset();
    });

    it('envoie la demande SANS mot de passe et lit « envoyée »', () => {
      const cmp = build('evaluateurs', []);
      api.createUser.and.returnValue(
        of(user({ id: 5, prenom: 'Rania', nom: 'Aouina', email: 'rania@epos.tn',
                  invitation: { envoyee: true, simulee: false } })),
      );
      cmp.openCreate();
      cmp.createForm.patchValue({ prenom: 'Rania', nom: 'Aouina', email: 'rania@epos.tn' });

      cmp.submitCreate();

      const body = api.createUser.calls.mostRecent().args[0];
      expect(body.password).toBeUndefined();
      expect(Object.keys(body)).not.toContain('password');
      expect(cmp.created()?.nomComplet).toBe('Rania Aouina');
      expect(cmp.invitationEtat(cmp.created()?.invitation)).toBe('envoyee');
    });

    it('messagerie désactivée : lit « simulée », jamais « envoyée »', () => {
      const cmp = build('evaluateurs', []);
      api.createUser.and.returnValue(
        of(user({ id: 5, email: 'r@epos.tn', invitation: { envoyee: true, simulee: true } })),
      );
      cmp.openCreate();
      cmp.createForm.patchValue({ prenom: 'R', nom: 'A', email: 'r@epos.tn' });

      cmp.submitCreate();

      expect(cmp.invitationEtat(cmp.created()?.invitation)).toBe('simulee');
    });

    it('panne SMTP : lit « échec » — le compte existe, le mail non', () => {
      const cmp = build('evaluateurs', []);
      expect(cmp.invitationEtat({ envoyee: false, simulee: false })).toBe('echec');
      expect(cmp.invitationEtat(null)).toBe('inconnu');
    });

    it('renvoyer l’invitation : le résultat est attaché à la ligne, le 403 est nominatif', () => {
      const target = user({ id: 5, email: 'r@epos.tn' });
      const cmp = build('evaluateurs', [target]);
      api.resendInvitation.and.returnValue(of({ envoyee: true, simulee: false }));

      cmp.renvoyerInvitation(target);

      expect(api.resendInvitation).toHaveBeenCalledWith(5);
      expect(cmp.renvoiResultat()).toEqual({ userId: 5, statut: { envoyee: true, simulee: false } });
      expect(cmp.renvoiEnCours()).toBeNull();

      api.resendInvitation.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 403, error: null })),
      );
      cmp.renvoyerInvitation(target);
      expect(cmp.renvoiErreur()?.message).toContain('périmètre');
    });
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

    it('« admin » liste tout le monde — désactivés sur demande (#437 : actifs par défaut)', () => {
      const cmp = build('admin', [
        user({ id: 1, nom: 'Actif' }),
        user({ id: 2, nom: 'Retire', isActive: false }),
      ]);

      expect(cmp.rows().map((u) => u.nom)).toEqual(['Actif']);
      cmp.etatFilter.set('tous');
      expect(cmp.rows().length).toBe(2);
      cmp.etatFilter.set('retires');
      expect(cmp.rows().map((u) => u.nom)).toEqual(['Retire']);
      expect(cmp.totalScoped()).toBe(2);
    });
  });

  // ---- #436 / #437 -------------------------------------------------------------------

  describe('#437 — filtres de l’écran Utilisateurs', () => {
    const RESP_CT = user({ id: 3, nom: 'Resp', roles: [{ role: 'RESPONSABLE_MATIERE', matiereId: 1 }] });
    const RESP_AUTRE = user({ id: 4, nom: 'Autre', roles: [{ role: 'RESPONSABLE_MATIERE', matiereId: 2 }] });
    const EVAL = user({ id: 5, nom: 'Eval' });

    it('rôle puis matière : ne garde que les responsables de la matière choisie', () => {
      const cmp = build('admin', [RESP_CT, RESP_AUTRE, EVAL]);
      cmp.onRoleFilterChange('RESPONSABLE_MATIERE');
      expect(cmp.rows().map((u) => u.nom)).toEqual(['Autre', 'Resp']);
      cmp.onMatiereFilterChange('1');
      expect(cmp.rows().map((u) => u.nom)).toEqual(['Resp']);
      // changer de rôle efface le filtre matière (il n'a plus de sens)
      cmp.onRoleFilterChange('EVALUATEUR');
      expect(cmp.matiereFilter()).toBeNull();
      expect(cmp.rows().map((u) => u.nom)).toEqual(['Eval']);
      cmp.reinitialiserFiltres();
      expect(cmp.rows().length).toBe(3);
      expect(cmp.filtresActifs()).toBeFalse();
    });
  });

  describe('#436 — nommer une personne existante / ajouter un rôle', () => {
    beforeEach(() => api.addRoles.calls.reset());

    it('« évaluateurs » : les candidats sont les actifs SANS le rôle, la nomination ajoute EVALUATEUR', () => {
      const dejaEval = user({ id: 1, nom: 'Deja' });
      const resp = user({ id: 2, nom: 'Resp', roles: [{ role: 'RESPONSABLE_MATIERE', matiereId: 1 }] });
      const retire = user({ id: 3, nom: 'Ferme', isActive: false, roles: [] });
      api.addRoles.and.returnValue(of(void 0));
      const cmp = build('evaluateurs', [dejaEval, resp, retire]);

      expect(cmp.appointRole()).toBe('EVALUATEUR');
      expect(cmp.appointCandidates().map((u) => u.nom)).toEqual(['Resp']);

      cmp.openAppoint();
      cmp.submitAppoint();
      expect(cmp.appointError()).toBe('Choisissez la personne.');
      expect(api.addRoles).not.toHaveBeenCalled();

      cmp.onAppointUserChange('2');
      cmp.submitAppoint();
      expect(api.addRoles).toHaveBeenCalledWith(2, [{ role: 'EVALUATEUR', matiereId: null }]);
    });

    it('« co-responsables » : la nomination ajoute RESPONSABLE_MATIERE sur la matière choisie (inchangé)', () => {
      const cible = user({ id: 2, nom: 'Cible' });
      api.addRoles.and.returnValue(of(void 0));
      const cmp = build('co-responsables', [cible]);
      cmp.openAppoint();
      cmp.onAppointMatiereChange('1');
      cmp.onAppointUserChange('2');
      cmp.submitAppoint();
      expect(api.addRoles).toHaveBeenCalledWith(2, [{ role: 'RESPONSABLE_MATIERE', matiereId: 1 }]);
    });

    it('admin : « Ajouter un rôle » n’envoie que les rôles cochés NON déjà portés', () => {
      const u = user({ id: 7, nom: 'Compte', roles: [{ role: 'EVALUATEUR', matiereId: null }] });
      api.addRoles.and.returnValue(of(void 0));
      const cmp = build('admin', [u]);

      cmp.openRoleAdd(u);
      expect(cmp.roleAddUserId()).toBe(7);
      cmp.roleAddForm.patchValue({ evaluateur: true }); // déjà porté → ignoré
      cmp.submitRoleAdd(u);
      expect(api.addRoles).not.toHaveBeenCalled();
      expect(cmp.roleAddError()).toContain('déjà');

      cmp.roleAddForm.patchValue({ responsable: true, respMatiereId: null });
      cmp.submitRoleAdd(u);
      expect(cmp.roleAddError()).toContain('matière');

      cmp.roleAddForm.patchValue({ responsable: true, respMatiereId: 1 });
      cmp.submitRoleAdd(u);
      expect(api.addRoles).toHaveBeenCalledWith(7, [{ role: 'RESPONSABLE_MATIERE', matiereId: 1 }]);
      expect(cmp.roleAddUserId()).toBeNull();
    });

    it('admin : le refus du serveur est affiché mot pour mot', () => {
      const u = user({ id: 7, nom: 'Compte', roles: [] });
      api.addRoles.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 400, error: { message: 'La matière « Chimie » est retirée du catalogue.' } })),
      );
      const cmp = build('admin', [u]);
      cmp.openRoleAdd(u);
      cmp.roleAddForm.patchValue({ superAdmin: true });
      cmp.submitRoleAdd(u);
      expect(cmp.roleAddError()).toContain('retirée du catalogue');
      expect(cmp.roleAddUserId()).toBe(7);
    });
  });
});
