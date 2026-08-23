import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { of } from 'rxjs';
import { EtudiantsComponent } from './etudiants.component';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import { EtudiantSummary, BulkEnrolResult } from '../../../core/api/models';

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
});
