import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { EtudiantsComponent } from './etudiants.component';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import { EtudiantSummary, BulkEnrolResult, BulkRetraitResult, ParticipationSummary } from '../../../core/api/models';

function etu(id: number, nom: string): EtudiantSummary {
  return { id, nom, prenom: 'P' + id, numero_inscription: 'N' + id, email: undefined };
}

describe('EtudiantsComponent — #186 sélection groupée', () => {
  let component: EtudiantsComponent;
  let scoring: jasmine.SpyObj<ScoringApiService>;

  beforeEach(() => {
    scoring = jasmine.createSpyObj('ScoringApiService', [
      'listParticipations',
      'listEtudiants',
      'listLots',
      'enrolParticipationsBulk',
      'retirerParticipationsBulk',
    ]);
    scoring.listParticipations.and.returnValue(of([]));
    scoring.listEtudiants.and.returnValue(of([etu(1, 'Alpha'), etu(2, 'Beta'), etu(3, 'Gamma')]));
    scoring.listLots.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [EtudiantsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ScoringApiService, useValue: scoring },
        ExamenWorkspaceStore,
      ],
    });

    const fixture = TestBed.createComponent(EtudiantsComponent);
    component = fixture.componentInstance;
    // input.required<string>() — simulate the parent-bound id.
    (component as any).id = () => '10';
    fixture.detectChanges();
  });

  it('toggleSelectAllFiltered sélectionne uniquement le filtre ACTIF', () => {
    component.search.set('Alpha');
    component.toggleSelectAllFiltered();

    expect(component.selectedCount()).toBe(1);
    expect(component.isSelected(1)).toBeTrue();
    expect(component.isSelected(2)).toBeFalse();
  });

  it('allFilteredSelected reflète uniquement les lignes visibles', () => {
    component.toggleSelect(1);
    component.toggleSelect(2);
    expect(component.allFilteredSelected()).toBeFalse();

    component.toggleSelect(3);
    expect(component.allFilteredSelected()).toBeTrue();
  });

  it('enrolSelected appelle le bulk avec les ids sélectionnés et vide la sélection au succès', () => {
    const result: BulkEnrolResult = { total: 2, enrolled: 2, alreadyEnrolled: 0, errors: 0, lignes: [] };
    scoring.enrolParticipationsBulk.and.returnValue(of(result));
    scoring.listParticipations.and.returnValue(of([]));

    component.toggleSelect(1);
    component.toggleSelect(2);
    component.enrolSelected();

    expect(scoring.enrolParticipationsBulk).toHaveBeenCalledWith(10, [1, 2]);
    expect(component.selectedCount()).toBe(0);
    expect(component.bulkResult()).toEqual(result);
  });

  it('un bilan avec errors > 0 garde le détail par étudiant (pas seulement les compteurs)', () => {
    const result: BulkEnrolResult = {
      total: 2,
      enrolled: 1,
      alreadyEnrolled: 0,
      errors: 1,
      lignes: [
        { etudiantId: 1, nom: 'Alpha', prenom: 'P1', statut: 'ENROLLED', message: 'Inscrit.' },
        { etudiantId: 99, nom: null, prenom: null, statut: 'ERROR', message: 'Étudiant introuvable : 99' },
      ],
    };
    scoring.enrolParticipationsBulk.and.returnValue(of(result));

    component.toggleSelect(1);
    component.enrolSelected();

    expect(component.bulkErrorLignes(component.bulkResult()!)).toEqual([result.lignes[1]]);
  });

  // ---- #435 — retrait groupé, symétrique de l'ajout ----------------------------

  function part(id: number, etudiantId: number, ordre: number | null): ParticipationSummary {
    return { id, examen_id: 10, num_echantillon: null, note: null, est_present: null, etudiantId, lotId: null, ordre_import: ordre };
  }

  describe('#435 retrait groupé', () => {
    beforeEach(() => {
      // Trois inscrits, dans l'ordre du fichier 3-1-2 : le tri suit ordre_import, pas l'id.
      scoring.listParticipations.and.returnValue(of([part(101, 1, 3), part(102, 2, 1), part(103, 3, 2)]));
      const fixture = TestBed.createComponent(EtudiantsComponent);
      component = fixture.componentInstance;
      (component as any).id = () => '10';
      fixture.detectChanges();
    });

    it('le listing suit l’ordre du fichier importé (ordre_import), pas l’id', () => {
      expect(component.rows().map((r) => r.participationId)).toEqual([102, 103, 101]);
    });

    it('« Tout sélectionner » du listing ne coche que le filtre ACTIF', () => {
      component.rosterSearch.set('Beta');
      component.toggleSelectAllRosterFiltered();
      expect([...component.rosterSelectedIds()]).toEqual([102]);
      expect(component.allRosterFilteredSelected()).toBeTrue();
      component.rosterSearch.set('');
      expect(component.allRosterFilteredSelected()).toBeFalse();
    });

    it('sans confirmation, rien ne part : askBulkRemove ouvre le panneau et n’appelle pas le serveur', () => {
      component.toggleRosterSelect(101);
      component.askBulkRemove();
      expect(component.confirmBulkRemove()).toBeTrue();
      expect(scoring.retirerParticipationsBulk).not.toHaveBeenCalled();
    });

    it('retirerSelection retire les lignes RETIRÉES, garde les refusées, conserve l’ordre, vide la sélection', () => {
      const result: BulkRetraitResult = {
        total: 2, retires: 1, introuvables: 0, erreurs: 1,
        lignes: [
          { participationId: 102, nom: 'Beta', prenom: 'P2', statut: 'RETIRE', message: 'Retiré.' },
          { participationId: 103, nom: 'Gamma', prenom: 'P3', statut: 'ERREUR', message: 'déjà dans un circuit' },
        ],
      };
      scoring.retirerParticipationsBulk.and.returnValue(of(result));

      component.toggleRosterSelect(102);
      component.toggleRosterSelect(103);
      component.askBulkRemove();
      component.retirerSelection();

      expect(scoring.retirerParticipationsBulk).toHaveBeenCalledWith(10, [102, 103]);
      // 102 est parti ; 103 (refusé) reste, et l'ordre 103 → 101 est celui du fichier.
      expect(component.rows().map((r) => r.participationId)).toEqual([103, 101]);
      expect(component.rosterSelectedCount()).toBe(0);
      expect(component.confirmBulkRemove()).toBeFalse();
      expect(component.bulkRemoveLignesEchec(component.bulkRemoveResult()!).map((l) => l.nom)).toEqual(['Gamma']);
    });

    it('une sélection vide ne déclenche rien', () => {
      component.retirerSelection();
      expect(scoring.retirerParticipationsBulk).not.toHaveBeenCalled();
    });
  });
});
