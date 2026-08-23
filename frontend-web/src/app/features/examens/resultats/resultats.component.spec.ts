import { TestBed } from '@angular/core/testing';
import { fakeAsync, flush } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { ResultatsComponent } from './resultats.component';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import {
  ExamenResult,
  GrilleDetail,
  ParticipationSummary,
  StationGrilleSnapshot,
  StationSummary,
} from '../../../core/api/models';

/**
 * #355 — écran de délibération (ADR-0021 D4). Ces specs épinglent :
 * la source de barème snapshot-d'abord (repli grille vivante NOMMÉ),
 * l'agrégation VERROUILLÉES-seulement (règle de verdict #297),
 * la distribution/concentration d'échec, le clic puce → cellule,
 * et — régression — la règle « rang 0 = pas de verdict » du classement.
 */
describe('ResultatsComponent — #355 délibération', () => {
  let scoring: jasmine.SpyObj<ScoringApiService>;
  let examApi: jasmine.SpyObj<ExamApiService>;

  const stations: StationSummary[] = [
    { id: 101, nom: 'Chimie analytique', ordre: 1, hasGrille: true },
    { id: 102, nom: 'Botanique', ordre: 2, hasGrille: true },
    { id: 103, nom: 'Galénique', ordre: 3, hasGrille: true }, // jamais notée
  ];

  const snapshots: StationGrilleSnapshot[] = [
    { stationId: 101, grilleId: 201, nom: 'Grille chimie', noteMax: 20, items: [] },
    { stationId: 102, grilleId: 202, nom: 'Grille bota', noteMax: 10, items: [] },
  ];

  function res(
    participationId: number,
    nom: string,
    cells: { stationId: number; notationId: number; score: number; verrouillee: boolean }[],
  ): ExamenResult {
    return {
      participationId,
      etudiantId: participationId,
      numeroInscription: 'N' + participationId,
      nom,
      prenom: 'P',
      numEchantillon: null,
      totalScore: cells.reduce((s, c) => s + c.score, 0),
      stationsNotees: cells.length,
      stations: cells.map((c) => ({
        notationId: c.notationId,
        stationId: c.stationId,
        grilleId: null,
        score: c.score,
        verrouillee: c.verrouillee,
      })),
    };
  }

  // Alice : 8/20 verrouillée (échec) + 4/10 verrouillée (échec).
  // Bob : 15/20 verrouillée + 9/10 verrouillée.
  // Carla : 12/20 NON verrouillée (en cours), rien ailleurs.
  const results: ExamenResult[] = [
    res(1, 'Alice A', [
      { stationId: 101, notationId: 11, score: 8, verrouillee: true },
      { stationId: 102, notationId: 12, score: 4, verrouillee: true },
    ]),
    res(2, 'Bob B', [
      { stationId: 101, notationId: 21, score: 15, verrouillee: true },
      { stationId: 102, notationId: 22, score: 9, verrouillee: true },
    ]),
    res(3, 'Carla C', [{ stationId: 101, notationId: 31, score: 12, verrouillee: false }]),
  ];

  const participations: ParticipationSummary[] = [1, 2, 3].map((id) => ({
    id,
    examen_id: 77,
    num_echantillon: null,
    note: null,
    est_present: true,
    etudiantId: id,
    lotId: null,
    ordre_import: null,
  })) as unknown as ParticipationSummary[];

  beforeEach(() => {
    scoring = jasmine.createSpyObj('ScoringApiService', [
      'getExamenResults',
      'getExamenGrillesSnapshot',
      'listParticipations',
      'getNotationItems',
      'listReajustements',
      'reajusterNotation',
    ]);
    examApi = jasmine.createSpyObj('ExamApiService', [
      'listStations',
      'getStationGrille',
      'changerStatut',
    ]);

    scoring.getExamenResults.and.returnValue(of(results));
    scoring.getExamenGrillesSnapshot.and.returnValue(of(snapshots));
    scoring.listParticipations.and.returnValue(of(participations));
    scoring.getNotationItems.and.returnValue(of([]));
    scoring.listReajustements.and.returnValue(of([]));
    examApi.listStations.and.returnValue(of(stations));
    examApi.getStationGrille.and.returnValue(
      of({ id: 201, nom: 'Grille vivante', noteMax: 20, items: [] } as GrilleDetail),
    );

    TestBed.configureTestingModule({
      imports: [ResultatsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ScoringApiService, useValue: scoring },
        { provide: ExamApiService, useValue: examApi },
        ExamenWorkspaceStore,
      ],
    });
  });

  function create(): ResultatsComponent {
    const fixture = TestBed.createComponent(ResultatsComponent);
    const component = fixture.componentInstance;
    (component as unknown as { id: () => string }).id = () => '77';
    fixture.detectChanges();
    return component;
  }

  // ---- source du barème : snapshot d'abord, repli NOMMÉ --------------------

  it('sert le barème depuis le snapshot scoring et ne touche PAS la grille vivante', () => {
    const c = create();

    expect(scoring.getExamenGrillesSnapshot).toHaveBeenCalledWith(77);
    expect(examApi.getStationGrille).not.toHaveBeenCalled();
    expect(c.baremeLiveStations()).toEqual([]);
    // le dénominateur vient du snapshot (st102 : /10, pas /20)
    expect(c.stationCols().find((col) => col.stationId === 102)?.noteMax).toBe(10);
  });

  it('sans snapshot (examen pré-V19), replie sur la grille vivante et le DIT', () => {
    scoring.getExamenGrillesSnapshot.and.returnValue(of([]));
    const c = create();

    expect(examApi.getStationGrille).toHaveBeenCalledTimes(2); // 101 + 102 (103 jamais notée)
    expect(c.baremeLiveStations()).toEqual([101, 102]);
  });

  it('panne d’exam-service : l’écran rend DEPUIS LES SNAPSHOTS, en-têtes dégradés', () => {
    examApi.listStations.and.returnValue(throwError(() => new Error('exam-service down')));
    const c = create();

    expect(c.rows().length).toBe(3); // pas d'écran d'erreur : la délibération vit
    expect(c.stationCols().find((col) => col.stationId === 101)?.nom).toBe('Station 101');
    expect(c.stationCols().find((col) => col.stationId === 102)?.noteMax).toBe(10); // snapshot
    expect(c.deliberation().find((d) => d.stationId === 101)?.nVerrouillees).toBe(2);
  });

  it('exam-service ET snapshots indisponibles : dénominateur /20 par défaut, écran vivant', () => {
    examApi.listStations.and.returnValue(throwError(() => new Error('exam-service down')));
    examApi.getStationGrille.and.returnValue(throwError(() => new Error('exam-service down')));
    scoring.getExamenGrillesSnapshot.and.returnValue(of([]));
    const c = create();

    expect(c.rows().length).toBe(3);
    expect(c.stationCols().find((col) => col.stationId === 101)?.noteMax).toBe(20);
    expect(c.baremeLiveStations()).toEqual([101, 102]);
  });

  it('panne de l’endpoint snapshot → même repli nommé, jamais un écran vide', () => {
    scoring.getExamenGrillesSnapshot.and.returnValue(throwError(() => new Error('down')));
    const c = create();

    expect(c.rows().length).toBe(3);
    expect(c.baremeLiveStations()).toEqual([101, 102]);
  });

  // ---- délibération : agrégats verrouillés-seulement -----------------------

  it('agrège les notes VERROUILLÉES seulement, l’en-cours est compté à part', () => {
    const c = create();
    const st101 = c.deliberation().find((d) => d.stationId === 101)!;

    expect(st101.nVerrouillees).toBe(2); // Alice 8 + Bob 15 — Carla (12, non verrouillée) exclue
    expect(st101.nEnCours).toBe(1);
    expect(st101.moyenne).toBeCloseTo(11.5, 5);
    expect(st101.mediane).toBeCloseTo(11.5, 5);
    expect(st101.min).toBe(8);
    expect(st101.max).toBe(15);
  });

  it('échec = note < 50 % du barème DE LA station (seuil par station, pas global)', () => {
    const c = create();
    const st101 = c.deliberation().find((d) => d.stationId === 101)!;
    const st102 = c.deliberation().find((d) => d.stationId === 102)!;

    expect(st101.echecs.map((e) => e.nom)).toEqual(['P Alice A']); // 8 < 10
    expect(st101.tauxEchec).toBe(50);
    expect(st102.echecs.map((e) => e.nom)).toEqual(['P Alice A']); // 4 < 5 ; Bob 9 passe
    expect(st102.tauxEchec).toBe(50);
  });

  it('histogramme : les bords tombent dans les bons bacs (0 → premier, noteMax → dernier)', () => {
    scoring.getExamenResults.and.returnValue(
      of([
        res(1, 'Zéro', [{ stationId: 101, notationId: 11, score: 0, verrouillee: true }]),
        res(2, 'Plein', [{ stationId: 101, notationId: 21, score: 20, verrouillee: true }]),
      ]),
    );
    const c = create();
    const bins = c.deliberation().find((d) => d.stationId === 101)!.bins;

    expect(bins.length).toBe(5);
    expect(bins[0].count).toBe(1); // 0
    expect(bins[4].count).toBe(1); // 20 = noteMax → dernier bac, pas un 6e
    expect(bins[0].sousSeuil).toBeTrue(); // 0–4 entièrement sous 10
    expect(bins[2].sousSeuil).toBeFalse(); // 8–12 chevauche le seuil → neutre
  });

  it('station sans aucune note verrouillée : n=0, aucun agrégat fabriqué', () => {
    scoring.getExamenResults.and.returnValue(
      of([res(3, 'Carla C', [{ stationId: 101, notationId: 31, score: 12, verrouillee: false }])]),
    );
    const c = create();
    const st101 = c.deliberation().find((d) => d.stationId === 101)!;

    expect(st101.nVerrouillees).toBe(0);
    expect(st101.nEnCours).toBe(1);
    expect(st101.moyenne).toBeNull();
    expect(st101.tauxEchec).toBeNull();
    expect(st101.echecs).toEqual([]);
  });

  it('les cartes se classent par taux d’échec décroissant, stations muettes en dernier', () => {
    scoring.getExamenResults.and.returnValue(
      of([
        // st101 : 1 échec sur 2 (50 %) · st102 : 2 échecs sur 2 (100 %)
        res(1, 'Alice A', [
          { stationId: 101, notationId: 11, score: 8, verrouillee: true },
          { stationId: 102, notationId: 12, score: 2, verrouillee: true },
        ]),
        res(2, 'Bob B', [
          { stationId: 101, notationId: 21, score: 15, verrouillee: true },
          { stationId: 102, notationId: 22, score: 3, verrouillee: true },
        ]),
        res(3, 'Carla C', [{ stationId: 103, notationId: 31, score: 4, verrouillee: false }]),
      ]),
    );
    const c = create();
    const ordres = c.deliberation().map((d) => d.stationId);

    expect(ordres[0]).toBe(102); // 100 % d'échec d'abord — la concentration se LIT
    expect(ordres[1]).toBe(101);
    expect(ordres[2]).toBe(103); // rien de verrouillé → en dernier
  });

  // ---- réajustement à portée de clic ---------------------------------------

  it('une puce d’échec ouvre la cellule étudiant×station (détail + réajustement)', fakeAsync(() => {
    const c = create();

    c.ouvrirCelluleDepuisDeliberation(1, 101);
    flush();

    expect(c.expandedKey()).toBe('1:101');
    expect(scoring.getNotationItems).toHaveBeenCalledWith(11);
    // cellule verrouillée → l'historique de réajustement se charge aussi
    expect(scoring.listReajustements).toHaveBeenCalledWith(11);
  }));

  // ---- régression : la règle de verdict du classement (#297) est intacte ----

  it('régression #297 : sans verrou sur TOUTES les stations, rang 0 et moyenne nulle', () => {
    const c = create();
    // 3 stations à l'examen, personne n'est verrouillé sur la 103 → aucun verdict
    for (const row of c.rows()) {
      expect(row.moyenne20).toBeNull();
      expect(row.rang).toBe(0);
    }
  });

  it('régression #297 : verrouillé partout → classé sur la moyenne /20', () => {
    examApi.listStations.and.returnValue(of(stations.slice(0, 2))); // examen à 2 stations
    const c = create();

    const alice = c.rows().find((r) => r.participationId === 1)!;
    const bob = c.rows().find((r) => r.participationId === 2)!;
    expect(bob.moyenne20).toBeCloseTo(16, 5); // (15+9)/30 ×20
    expect(bob.rang).toBe(1);
    expect(alice.moyenne20).toBeCloseTo(8, 5); // (8+4)/30 ×20
    expect(alice.rang).toBe(2);
    const carla = c.rows().find((r) => r.participationId === 3)!;
    expect(carla.rang).toBe(0); // note non verrouillée → toujours pas de verdict
  });
});
