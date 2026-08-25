import { TestBed } from '@angular/core/testing';
import { fakeAsync, flush } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { ResultatsComponent } from './resultats.component';
import { AiApiService } from '../../../core/api/ai-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import {
  ExamenResult,
  GrilleDetail,
  IndicesExamen,
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
  let ai: jasmine.SpyObj<AiApiService>;

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
      // #359-bis : les specs DOM flushent l'effect du panneau réclamations
      // (enfant du template) — son forkJoin exige ces deux méthodes.
      'listReclamations',
      'listEtudiants',
    ]);
    examApi = jasmine.createSpyObj('ExamApiService', [
      'listStations',
      'getStationGrille',
      'changerStatut',
    ]);
    ai = jasmine.createSpyObj('AiApiService', ['getIndices']);
    // Défaut : ai-service ABSENT — chaque spec existante exerce ainsi le
    // fail-soft (#359, ADR-0021 D4 : l'écran ne dépend jamais du module IA).
    ai.getIndices.and.returnValue(throwError(() => ({ status: 503 })));

    scoring.getExamenResults.and.returnValue(of(results));
    scoring.getExamenGrillesSnapshot.and.returnValue(of(snapshots));
    scoring.listParticipations.and.returnValue(of(participations));
    scoring.getNotationItems.and.returnValue(of([]));
    scoring.listReajustements.and.returnValue(of([]));
    scoring.listReclamations.and.returnValue(of([]));
    scoring.listEtudiants.and.returnValue(of([]));
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
        { provide: AiApiService, useValue: ai },
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

  // ---- #359 — indices psychométriques : fail-soft strict (ADR-0021 D4) ------

  function indicesPayload(): IndicesExamen {
    return {
      examen_id: 77,
      entrees_hash: 'a'.repeat(64),
      moteur_version: 'n5-test',
      exclusions: {
        saisi_par_null: 1,
        detail_incomplet: 2,
        notations_analysees: 4,
        sans_aucun_item: 1,
      },
      par_critere: [
        {
          item_id: 301,
          libelle: 'Pesée',
          type: 'NUMERIQUE',
          grille_id: 201,
          station_id: 101,
          difficulte: {
            code: 'DIFFICULTE', statut: 'CONCLUANT', n: 12,
            valeur: 0.42, ic: [0.31, 0.55], raison: null, details: {},
          },
          discrimination: {
            code: 'DISCRIMINATION', statut: 'NON_CONCLUANT', n: 8,
            valeur: null, ic: null,
            raison: 'non concluant — effectif insuffisant (n=8 < 15)', details: {},
          },
        },
      ],
      par_grille: [
        {
          grille_id: 201, station_id: 101,
          alpha_cronbach: {
            code: 'ALPHA_CRONBACH', statut: 'CONCLUANT', n: 15,
            valeur: 0.71, ic: [0.58, 0.81], raison: null, details: { k: 4 },
          },
        },
      ],
      par_station: [
        {
          station_id: 101,
          concentration_echec: {
            code: 'CONCENTRATION_ECHEC', statut: 'CONCLUANT', n: 12,
            valeur: 0.45, ic: null, raison: null,
            details: { p_value: 0.032, taux_autres: 0.2 },
          },
        },
      ],
    };
  }

  it('#359 : ai-service ABSENT → l\'écran reste intact, error() reste false', () => {
    // Le défaut du beforeEach est déjà un throwError 503 — on vérifie l'effet.
    const c = create();
    expect(c.error()).toBeFalse();
    expect(c.rows().length).toBe(3); // la table s'est construite sans le module IA
    expect(c.deliberation().length).toBeGreaterThan(0);
    expect(c.indicesEtat()).toBe('absents');
    expect(c.indices()).toBeNull();
  });

  it('#359 : 403 / 409 / 501 se replient sur le MÊME état absents (aucune distinction de panne)', () => {
    for (const status of [403, 409, 501]) {
      ai.getIndices.and.returnValue(throwError(() => ({ status })));
      const c = create();
      expect(c.indicesEtat()).withContext(`status ${status}`).toBe('absents');
      expect(c.error()).toBeFalse();
    }
  });

  it('#359 : payload nominal → prets, lookups par station et par item peuplés', () => {
    ai.getIndices.and.returnValue(of(indicesPayload()));
    const c = create();
    expect(c.indicesEtat()).toBe('prets');
    expect(c.alphaDe(101)?.valeur).toBeCloseTo(0.71, 5);
    expect(c.concentrationDe(101)?.valeur).toBeCloseTo(0.45, 5);
    expect(c.indiceCritereDe(301)?.difficulte.n).toBe(12);
    expect(c.alphaDe(999)).toBeNull(); // station sans indice → null, jamais inventé
  });

  it('#359 : un refus reste un refus — la raison du backend est servie VERBATIM', () => {
    ai.getIndices.and.returnValue(of(indicesPayload()));
    const c = create();
    const disc = c.indiceCritereDe(301)!.discrimination;
    expect(disc.statut).toBe('NON_CONCLUANT');
    expect(disc.valeur).toBeNull();
    expect(disc.raison).toBe('non concluant — effectif insuffisant (n=8 < 15)');
  });

  it('#359 : pLabel formate la p-value à la française, vide sans p_value', () => {
    ai.getIndices.and.returnValue(of(indicesPayload()));
    const c = create();
    expect(c.pLabel(c.concentrationDe(101)!)).toBe(', p=0,032');
    expect(c.pLabel(c.alphaDe(101)!)).toBe('');
  });

  // ---- #359-bis (S46) — VISIBILITÉ : ce qui se REND, pas ce qui se calcule ---
  // Constat de la passe navigateur de Nada : les indices étaient stylés comme
  // l'état absent (gris minuscule) et devenaient introuvables. Ces specs
  // épinglent le rendu DOM : le bloc contenu, le poids de lecture des valeurs,
  // le refus en pastille ambre (texte backend VERBATIM), et l'état absent qui
  // reste, LUI, discret.

  function createDom(): { c: ResultatsComponent; el: HTMLElement } {
    const fixture = TestBed.createComponent(ResultatsComponent);
    const c = fixture.componentInstance;
    (c as unknown as { id: () => string }).id = () => '77';
    fixture.detectChanges(); // ngOnInit → load() (observables synchrones)
    fixture.detectChanges(); // re-rendu après la pose des signaux
    return { c, el: fixture.nativeElement as HTMLElement };
  }

  it('#359-bis : indices prêts → bloc « Indices cohorte » contenu, valeur au poids de lecture', () => {
    ai.getIndices.and.returnValue(of(indicesPayload()));
    const { c, el } = createDom();
    expect(c.indicesEtat()).toBe('prets');

    const entetes = Array.from(el.querySelectorAll('p')).filter(
      (p) => p.textContent?.trim() === 'Indices cohorte',
    );
    expect(entetes.length).withContext('un en-tête par carte porteuse d\'indices').toBeGreaterThan(0);

    const bloc = entetes[0].closest('div.rounded-lg')!;
    expect(bloc.className).withContext('le bloc est CONTENU (fond + bord)').toContain('bg-gray-50');
    const valeur = Array.from(bloc.querySelectorAll('span.font-semibold')).find((s) =>
      /0[.,]71/.test(s.textContent ?? ''),
    );
    expect(valeur).withContext('α rendu en font-semibold, pas en gris discret').toBeDefined();
    expect(valeur!.className).toContain('text-gray-900');
  });

  it('#359-bis : un refus se rend en pastille ambre, texte backend VERBATIM dedans', () => {
    const payload = indicesPayload();
    payload.par_station[0].concentration_echec = {
      code: 'CONCENTRATION_ECHEC', statut: 'NON_CONCLUANT', n: 4,
      valeur: null, ic: null,
      raison: 'non concluant — effectif insuffisant (n=4 < 10)', details: {},
    };
    ai.getIndices.and.returnValue(of(payload));
    const { el } = createDom();

    const pastille = Array.from(el.querySelectorAll('span')).find(
      (s) => s.textContent?.trim() === 'non concluant — effectif insuffisant (n=4 < 10)',
    );
    expect(pastille).withContext('la raison du backend rendue telle quelle').toBeDefined();
    expect(pastille!.className).toContain('bg-amber-50');
    expect(pastille!.className).toContain('text-amber-800');
  });

  it('#359-bis : indices absents → AUCUN bloc sur les cartes, seule la note discrète', () => {
    // Défaut du beforeEach : throwError 503 → absents.
    const { c, el } = createDom();
    expect(c.indicesEtat()).toBe('absents');

    expect(el.textContent).not.toContain('Indices cohorte');
    const note = Array.from(el.querySelectorAll('span[role="status"]')).find(
      (s) => s.textContent?.trim() === 'Indices psychométriques non disponibles',
    );
    expect(note).withContext('le silence est interdit — la note discrète le dit').toBeDefined();
    expect(note!.className).withContext('l\'état ABSENT, lui, reste discret').toContain('text-gray-400');
  });
});
