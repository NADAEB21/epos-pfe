import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { SuiviComponent } from './suivi.component';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { DirectoryApiService } from '../../../core/api/directory-api.service';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import { ParticipationSummary, SuiviProgression } from '../../../core/api/models';

/**
 * #434 — « Démarrer le lot N — tous présents » demande confirmation : le bouton se lisait
 * « aller faire la présence » et démarrait la vague sans retour. Ces tests épinglent que
 * RIEN ne part avant « Oui, tous présents », que la confirmation appelle l'acte UNE fois,
 * et que l'effectif du lot est nommé.
 */
describe('SuiviComponent — #434 confirmation du démarrage', () => {
  let scoring: jasmine.SpyObj<ScoringApiService>;

  const progression: SuiviProgression = {
    lotOuvert: null,
    lotTermine: false,
    lotSuivant: { id: 5, numeroLot: 1, rotationsGenerees: false },
    stations: [],
  } as unknown as SuiviProgression;

  const part = (id: number, lotId: number | null): ParticipationSummary => ({
    id, examen_id: 10, num_echantillon: null, note: null, est_present: null, etudiantId: id, lotId,
  });

  function build(): SuiviComponent {
    scoring = jasmine.createSpyObj('ScoringApiService', [
      'listParticipations', 'listEtudiants', 'getProgression', 'listRotationsByStation',
      'presenceEtDemarrer', 'ouvrirLot', 'listLots',
    ]);
    scoring.listParticipations.and.returnValue(of([part(1, 5), part(2, 5), part(3, 5), part(4, 6)]));
    scoring.listEtudiants.and.returnValue(of([]));
    scoring.getProgression.and.returnValue(of(progression));
    scoring.listRotationsByStation.and.returnValue(of([]));
    scoring.listLots.and.returnValue(of([]));
    scoring.presenceEtDemarrer.and.returnValue(of({ lotId: 5, presents: 3, absents: 0, rotations: 2, assignments: 3, avertissement: null }));
    const examApi = jasmine.createSpyObj('ExamApiService', ['listStations', 'getExamen']);
    examApi.listStations.and.returnValue(of([]));
    examApi.getExamen.and.returnValue(of(null));
    const directory = jasmine.createSpyObj('DirectoryApiService', ['listUsers']);
    directory.listUsers.and.returnValue(of([]));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [SuiviComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ScoringApiService, useValue: scoring },
        { provide: ExamApiService, useValue: examApi },
        { provide: DirectoryApiService, useValue: directory },
        ExamenWorkspaceStore,
      ],
    });
    const fixture = TestBed.createComponent(SuiviComponent);
    const c = fixture.componentInstance;
    (c as unknown as { id: () => string }).id = () => '10';
    fixture.detectChanges();
    return c;
  }

  it('le conducteur du premier lot est affiché et nomme l’effectif du lot', () => {
    const c = build();
    expect(c.conducteurPremierLot()?.id).toBe(5);
    expect(c.effectifLot(5)).toBe(3);
  });

  it('cliquer « Démarrer » ouvre la confirmation et n’appelle PAS le serveur', () => {
    const c = build();
    c.demanderDemarrage(5);
    expect(c.confirmDemarrage()).toBe(5);
    expect(scoring.presenceEtDemarrer).not.toHaveBeenCalled();
  });

  it('« Annuler » referme sans rien envoyer', () => {
    const c = build();
    c.demanderDemarrage(5);
    c.annulerDemarrage();
    expect(c.confirmDemarrage()).toBeNull();
    expect(scoring.presenceEtDemarrer).not.toHaveBeenCalled();
  });

  it('« Oui, tous présents » appelle l’acte UNE fois, sur le bon lot, et referme le panneau', () => {
    const c = build();
    c.demanderDemarrage(5);
    c.confirmerDemarrage();
    expect(scoring.presenceEtDemarrer).toHaveBeenCalledTimes(1);
    expect(scoring.presenceEtDemarrer).toHaveBeenCalledWith(5);
    expect(c.confirmDemarrage()).toBeNull();
  });

  it('confirmer sans panneau ouvert ne fait rien', () => {
    const c = build();
    c.confirmerDemarrage();
    expect(scoring.presenceEtDemarrer).not.toHaveBeenCalled();
  });
});
