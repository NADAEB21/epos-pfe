import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { MatieresComponent } from './matieres.component';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { MatiereResponse } from '../../core/api/models';

/**
 * #134 — le catalogue des matières. Ce que ces tests épinglent : il n'existe
 * AUCUNE suppression (le retrait motivé est l'unique acte de fermeture, et il
 * est réversible), le motif est une condition, la provenance reste lisible,
 * et l'import colle ce qu'Excel produit (tabulation ou point-virgule).
 */
describe('MatieresComponent — catalogue, retrait motivé, import', () => {
  const api = {
    listMatieres: jasmine.createSpy('listMatieres'),
    listUsers: jasmine.createSpy('listUsers'),
    createMatiere: jasmine.createSpy('createMatiere'),
    updateMatiere: jasmine.createSpy('updateMatiere'),
    retirerMatiere: jasmine.createSpy('retirerMatiere'),
    reactiverMatiere: jasmine.createSpy('reactiverMatiere'),
    importMatieres: jasmine.createSpy('importMatieres'),
  };

  const matiere = (over: Partial<MatiereResponse>): MatiereResponse => ({
    id: 1,
    code: 'CHIM_THER',
    libelle: 'Chimie thérapeutique',
    active: true,
    ...over,
  });

  const ADMIN = {
    id: 9,
    email: 'admin@epos.tn',
    nom: 'Ben Ali',
    prenom: 'Aymen',
    isActive: true,
    createdAt: null,
    roles: [{ role: 'SUPER_ADMIN' as const, matiereId: null }],
  };

  function build(matieres: MatiereResponse[]) {
    api.listMatieres.and.returnValue(of(matieres));
    api.listUsers.and.returnValue(of([ADMIN]));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MatieresComponent],
      providers: [provideRouter([]), { provide: DirectoryApiService, useValue: api }],
    });
    return TestBed.createComponent(MatieresComponent).componentInstance;
  }

  beforeEach(() => {
    api.createMatiere.calls.reset();
    api.updateMatiere.calls.reset();
    api.retirerMatiere.calls.reset();
    api.reactiverMatiere.calls.reset();
    api.importMatieres.calls.reset();
  });

  describe('le motif est une condition, pas une décoration', () => {
    it('refuse le retrait sans motif, SANS appeler le serveur', () => {
      const cmp = build([matiere({})]);
      cmp.askRetirer(matiere({}));

      cmp.confirmRetirer();

      expect(api.retirerMatiere).not.toHaveBeenCalled();
      expect(cmp.actError()).toContain('obligatoire');
    });

    it('transmet le motif épuré des espaces', () => {
      api.retirerMatiere.and.returnValue(of(matiere({ active: false })));
      const cmp = build([matiere({})]);
      cmp.askRetirer(matiere({}));
      cmp.motif.set('  Fermée à la rentrée 2026  ');

      cmp.confirmRetirer();

      expect(api.retirerMatiere).toHaveBeenCalledWith(1, 'Fermée à la rentrée 2026');
    });

    it('affiche mot pour mot le refus du serveur', () => {
      api.retirerMatiere.and.returnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: { message: 'La matière « Chimie thérapeutique » est déjà retirée.' },
            }),
        ),
      );
      const cmp = build([matiere({})]);
      cmp.askRetirer(matiere({}));
      cmp.motif.set('encore');

      cmp.confirmRetirer();

      expect(cmp.actError()).toContain('déjà retirée');
    });
  });

  describe('la provenance répond à « pourquoi cette matière est-elle fermée ? »', () => {
    it('nomme la date, l’auteur et le motif', () => {
      const cmp = build([matiere({})]);
      const retiree = matiere({
        active: false,
        retiredAt: '2026-08-06T09:00:00',
        retiredBy: 9,
        retirementMotif: 'Fermée à la rentrée 2026',
      });

      const label = cmp.retraitLabel(retiree);

      expect(label).toContain('06/08/2026');
      expect(label).toContain('Aymen Ben Ali');
      expect(label).toContain('Fermée à la rentrée 2026');
    });
  });

  describe('réversibilité — un seul panneau ouvert à la fois', () => {
    it('rouvre une matière avec son motif', () => {
      api.reactiverMatiere.and.returnValue(of(matiere({})));
      const cmp = build([matiere({ active: false })]);
      cmp.askReactiver(matiere({ active: false }));
      cmp.motif.set('La matière reprend');

      cmp.confirmReactiver();

      expect(api.reactiverMatiere).toHaveBeenCalledWith(1, 'La matière reprend');
    });

    it('ouvrir un panneau ferme l’autre (le viewChild motif doit être unique)', () => {
      const cmp = build([matiere({}), matiere({ id: 2, active: false })]);
      cmp.askRetirer(matiere({}));
      cmp.askReactiver(matiere({ id: 2, active: false }));

      expect(cmp.confirmingRetrait()).toBeNull();
      expect(cmp.confirmingReactivation()).not.toBeNull();
    });

    it('Échap referme le panneau ouvert', () => {
      const cmp = build([matiere({})]);
      cmp.askRetirer(matiere({}));

      cmp.onEscape();

      expect(cmp.confirmingRetrait()).toBeNull();
    });
  });

  describe('liste — actives d’abord, recherche sur code + libellé', () => {
    it('trie les retirées après les actives', () => {
      const cmp = build([
        matiere({ id: 1, libelle: 'Aaa retirée', active: false }),
        matiere({ id: 2, code: 'ZZZ', libelle: 'Zzz active' }),
      ]);

      expect(cmp.rows().map((m) => m.id)).toEqual([2, 1]);
    });

    it('cherche aussi par code', () => {
      const cmp = build([
        matiere({ id: 1, code: 'CHIM_THER', libelle: 'Chimie thérapeutique' }),
        matiere({ id: 2, code: 'PHARMACO', libelle: 'Pharmacologie' }),
      ]);
      cmp.search.set('pharma');

      expect(cmp.rows().map((m) => m.id)).toEqual([2]);
    });
  });

  describe('import — le texte collé est celui d’Excel', () => {
    it('accepte le point-virgule ET la tabulation, ligne par ligne', () => {
      const cmp = build([]);

      const rows = cmp.parseImportText('BIOCHIM ; Biochimie clinique\nMICRO\tMicrobiologie\n\n');

      expect(rows).toEqual([
        { code: 'BIOCHIM', libelle: 'Biochimie clinique' },
        { code: 'MICRO', libelle: 'Microbiologie' },
      ]);
    });

    it('ne préjuge pas d’une ligne sans séparateur — le serveur rend le verdict', () => {
      const cmp = build([]);

      const rows = cmp.parseImportText('Biochimie clinique');

      expect(rows).toEqual([{ code: '', libelle: 'Biochimie clinique' }]);
    });

    it('refuse un envoi vide sans appeler le serveur', () => {
      const cmp = build([]);
      cmp.importText.set('   \n  ');

      cmp.submitImport();

      expect(api.importMatieres).not.toHaveBeenCalled();
      expect(cmp.importError()).toContain('au moins une ligne');
    });
  });

  describe('création / renommage', () => {
    it('affiche mot pour mot le 409 du serveur (code déjà pris, même retiré)', () => {
      api.createMatiere.and.returnValue(
        throwError(
          () =>
            new HttpErrorResponse({
              status: 409,
              error: {
                message:
                  'Le code « CHIM_THER » est déjà pris par la matière « Chimie thérapeutique » (retirée — rouvrez-la plutôt que de la recréer).',
              },
            }),
        ),
      );
      const cmp = build([]);
      cmp.openCreate();
      cmp.form.setValue({ code: 'CHIM_THER', libelle: 'Chimie' });

      cmp.submitForm();

      expect(cmp.submitError()).toContain('rouvrez-la');
    });

    it('renomme via PUT sur l’id, jamais par recréation', () => {
      api.updateMatiere.and.returnValue(of(matiere({ libelle: 'Pharmacologie' })));
      const cmp = build([matiere({ id: 2, code: 'PHARMACO', libelle: 'Pharmacolgie' })]);
      cmp.openRename(matiere({ id: 2, code: 'PHARMACO', libelle: 'Pharmacolgie' }));
      cmp.form.controls.libelle.setValue('Pharmacologie');

      cmp.submitForm();

      expect(api.updateMatiere).toHaveBeenCalledWith(2, {
        code: 'PHARMACO',
        libelle: 'Pharmacologie',
      });
      expect(api.createMatiere).not.toHaveBeenCalled();
    });
  });
});
