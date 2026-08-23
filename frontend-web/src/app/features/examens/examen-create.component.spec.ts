import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ExamenCreateComponent } from './examen-create.component';
import { ExamApiService } from '../../core/api/exam-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { AuthStore } from '../../core/auth/auth.store';
import { MatiereResponse } from '../../core/api/models';

/**
 * #303/#304 — la famille « matière retirée » côté création d'examen. Ce que ces tests
 * épinglent : le sélecteur se rend dès UNE option assignable (le seuil `> 1` confondait
 * « une seule option » et « aucun choix » et bloquait tout l'écran) ; un pré-remplissage
 * vers une matière retirée est CORRIGÉ au chargement du catalogue ; la branche zéro-option
 * NOMME la matière fermée au lieu d'afficher son libellé comme si de rien n'était ; et le
 * refus nominatif du backend s'affiche mot pour mot.
 */
describe('ExamenCreateComponent — matière retirée (#303/#304)', () => {
  const examApi = { createExamen: jasmine.createSpy('createExamen') };
  const directoryApi = { listMatieres: jasmine.createSpy('listMatieres') };

  const matiere = (over: Partial<MatiereResponse>): MatiereResponse => ({
    id: 1,
    code: 'TOXICO',
    libelle: 'Toxicologie',
    active: true,
    ...over,
  });

  function build(scopeIds: number[], catalogue: MatiereResponse[] | 'error') {
    directoryApi.listMatieres.and.returnValue(
      catalogue === 'error' ? throwError(() => new Error('down')) : of(catalogue),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ExamenCreateComponent],
      providers: [
        provideRouter([]),
        { provide: ExamApiService, useValue: examApi },
        { provide: DirectoryApiService, useValue: directoryApi },
        { provide: AuthStore, useValue: { responsableMatiereIds: () => scopeIds } },
      ],
    });
    return TestBed.createComponent(ExamenCreateComponent).componentInstance;
  }

  beforeEach(() => {
    examApi.createExamen.calls.reset();
  });

  describe('#304 — 2 matières dont une retirée : le formulaire VIT', () => {
    it('le sélecteur se rend avec la seule option active, pré-sélectionnée', () => {
      const cmp = build(
        [10, 1],
        [
          matiere({ id: 10, code: 'PHARMA', libelle: 'Pharmacognosie', active: false }),
          matiere({ id: 1 }),
        ],
      );

      expect(cmp.matiereOptions().map((m) => m.id)).toEqual([1]);
      // Une seule option active → fixée d'office : le responsable n'est plus bloqué.
      expect(cmp.form.controls.matiereId.value).toBe(1);
      expect(cmp.matieresRetirees().map((m) => m.libelle)).toEqual(['Pharmacognosie']);
    });
  });

  describe('#303 — responsable mono-matière dont la matière est retirée', () => {
    it('le pré-remplissage optimiste est CORRIGÉ au chargement du catalogue', () => {
      const cmp = build([10], [
        matiere({ id: 10, code: 'PHARMA', libelle: 'Pharmacognosie', active: false }),
      ]);

      // Le constructeur avait posé 10 (résilience catalogue-en-panne) ; le
      // catalogue chargé le retire — l'écran n'envoie plus vers une matière fermée.
      expect(cmp.form.controls.matiereId.value).toBe(0);
      expect(cmp.matiereOptions()).toEqual([]);
      expect(cmp.catalogueLoaded()).toBeTrue();
      expect(cmp.matieresRetirees().map((m) => m.libelle)).toEqual(['Pharmacognosie']);
    });
  });

  describe('les cas qui ne doivent PAS régresser', () => {
    it('mono-matière ACTIVE : pré-remplie, une option au sélecteur', () => {
      const cmp = build([1], [matiere({ id: 1 })]);

      expect(cmp.form.controls.matiereId.value).toBe(1);
      expect(cmp.matiereOptions().map((m) => m.id)).toEqual([1]);
    });

    it('catalogue en PANNE : le pré-remplissage mono-matière survit (dégradé, jamais « retirée »)', () => {
      const cmp = build([1], 'error');

      expect(cmp.form.controls.matiereId.value).toBe(1);
      expect(cmp.catalogueLoaded()).toBeFalse();
      expect(cmp.matieresRetirees()).toEqual([]);
    });
  });

  describe('#303 — le refus nominatif du backend s\'affiche mot pour mot', () => {
    it('un 400 avec message le fait primer sur le générique', () => {
      const refus =
        'Création impossible : la matière « Pharmacognosie » a été retirée du catalogue par l\'administration.';
      examApi.createExamen.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 400, error: { message: refus } })),
      );
      const cmp = build([1], [matiere({ id: 1 })]);
      cmp.form.patchValue({ nom: 'Session juin', dateExamen: '2026-08-20' });

      cmp.onSubmit();

      expect(cmp.submitError()).toBe('validation');
      expect(cmp.serverMessage()).toBe(refus);
    });

    it('un 400 SANS message retombe sur le générique (serverMessage null)', () => {
      examApi.createExamen.and.returnValue(
        throwError(() => new HttpErrorResponse({ status: 400, error: {} })),
      );
      const cmp = build([1], [matiere({ id: 1 })]);
      cmp.form.patchValue({ nom: 'Session juin', dateExamen: '2026-08-20' });

      cmp.onSubmit();

      expect(cmp.submitError()).toBe('validation');
      expect(cmp.serverMessage()).toBeNull();
    });
  });
});
