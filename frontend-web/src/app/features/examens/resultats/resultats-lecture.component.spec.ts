import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { of, throwError } from 'rxjs';
import { ResultatsComponent } from './resultats.component';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { AiApiService } from '../../../core/api/ai-api.service';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import { ExamenResult, GrilleDetail, ParticipationSummary, StationGrilleSnapshot, StationSummary } from '../../../core/api/models';

/**
 * #401 (ADR-0030 D4 révisé, décision Nada 2026-09-04) — LA LECTURE DÉLIBÉRÉE
 * EST LE RÉSULTAT. Ce que ces specs verrouillent, avec la forme EXACTE des
 * lignes que scoring sert (`scoreDelibere` / `maxDelibere` par station,
 * `totalDelibere` par ligne) :
 *  - version servie → classement, moyenne /20, mention, cartes KPI et CSV sous
 *    le barème délibéré ; l'origine reste calculée et exportée (la trace) ;
 *  - dénominateur PAR ÉTUDIANT (somme des max délibérés des stations passées),
 *    même garde #297 (verrou sur toutes les stations) ;
 *  - station EXCLUE hors des deux sommes, jamais un échec ;
 *  - sans version, ou version non servie (#399) → lecture d'origine, inchangée ;
 *  - invariant Σ scoreDelibere == totalDelibere : une incohérence n'est JAMAIS
 *    classée en silence — origine + pastille rouge.
 */
describe('ResultatsComponent — la lecture délibérée EST le résultat (#401)', () => {
  let scoring: jasmine.SpyObj<ScoringApiService>;
  let examApi: jasmine.SpyObj<ExamApiService>;
  let ai: jasmine.SpyObj<AiApiService>;

  const stations: StationSummary[] = [
    { id: 101, nom: 'Chimie analytique', ordre: 1, hasGrille: true },
    { id: 102, nom: 'Botanique', ordre: 2, hasGrille: true },
  ];
  const snapshots: StationGrilleSnapshot[] = [
    { stationId: 101, grilleId: 201, nom: 'Grille chimie', noteMax: 20, items: [] },
    { stationId: 102, grilleId: 202, nom: 'Grille bota', noteMax: 10, items: [] },
  ];

  interface Cell {
    stationId: number; score: number; verrouillee?: boolean;
    /** null = station EXCLUE (contrat BaremeDeliberationEngine : clé absente). */
    delib?: { score: number; max: number } | null;
  }

  /** Une ligne telle que scoring la sert : version + délibéré par station + total délibéré sommé. */
  function res(id: number, nom: string, cells: Cell[], version: number | null, totalDelibereOverride?: number | null): ExamenResult {
    const stationsOut = cells.map((c) => ({
      notationId: id * 10 + c.stationId,
      stationId: c.stationId,
      grilleId: null,
      score: c.score,
      verrouillee: c.verrouillee ?? true,
      maxOriginal: snapshots.find((s) => s.stationId === c.stationId)?.noteMax ?? null,
      scoreDelibere: version == null ? null : c.delib === null ? null : (c.delib?.score ?? c.score),
      maxDelibere: version == null ? null : c.delib === null ? null : (c.delib?.max ?? snapshots.find((s) => s.stationId === c.stationId)?.noteMax ?? null),
    }));
    const totalDelibere = version == null ? null
      : stationsOut.reduce((s, c) => s + (c.scoreDelibere ?? 0), 0);
    return {
      participationId: id, etudiantId: id, numeroInscription: 'N' + id, nom, prenom: 'P', numEchantillon: null,
      totalScore: cells.reduce((s, c) => s + c.score, 0),
      stationsNotees: cells.length,
      stations: stationsOut,
      denominateurOriginal: 30,
      totalDelibere: totalDelibereOverride === undefined ? totalDelibere : totalDelibereOverride,
      denominateurDelibere: version == null ? null : stationsOut.reduce((s, c) => s + (c.maxDelibere ?? 0), 0),
      baremeVersion: version,
    };
  }

  const participations = (ids: number[]): ParticipationSummary[] =>
    ids.map((id) => ({ id, examen_id: 77, num_echantillon: null, note: null, est_present: true, etudiantId: id, lotId: null, ordre_import: null })) as unknown as ParticipationSummary[];

  function build(results: ExamenResult[]) {
    scoring = jasmine.createSpyObj('ScoringApiService', [
      'getExamenResults', 'getExamenGrillesSnapshot', 'listParticipations', 'getNotationItems',
      'listReajustements', 'reajusterNotation', 'listReclamations', 'listEtudiants',
    ]);
    examApi = jasmine.createSpyObj('ExamApiService', ['listStations', 'getStationGrille', 'changerStatut']);
    ai = jasmine.createSpyObj('AiApiService', ['getIndices']);
    ai.getIndices.and.returnValue(throwError(() => ({ status: 503 })));
    scoring.getExamenResults.and.returnValue(of(results));
    scoring.getExamenGrillesSnapshot.and.returnValue(of(snapshots));
    scoring.listParticipations.and.returnValue(of(participations(results.map((r) => r.participationId))));
    scoring.getNotationItems.and.returnValue(of([]));
    scoring.listReajustements.and.returnValue(of([]));
    scoring.listReclamations.and.returnValue(of([]));
    scoring.listEtudiants.and.returnValue(of([]));
    examApi.listStations.and.returnValue(of(stations));
    examApi.getStationGrille.and.returnValue(of({ id: 201, nom: 'Grille vivante', noteMax: 20, items: [] } as GrilleDetail));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ResultatsComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        { provide: ScoringApiService, useValue: scoring },
        { provide: ExamApiService, useValue: examApi },
        { provide: AiApiService, useValue: ai },
        ExamenWorkspaceStore,
      ],
    });
    const fixture = TestBed.createComponent(ResultatsComponent);
    (fixture.componentInstance as unknown as { id: () => string }).id = () => '77';
    fixture.detectChanges();
    fixture.detectChanges();
    return { c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  const row = (c: ResultatsComponent, nom: string) => c.rows().find((r) => r.nom.endsWith(nom))!;
  const ths = (el: HTMLElement) => Array.from(el.querySelectorAll('th')).map((th) => th.textContent?.replace(/\s+/g, ' ').trim());

  // Barème v1 : un critère de 5 points retiré sur la station 101 (max 20 → 15), scores inchangés.
  const V1 = [
    res(1, 'Alice', [{ stationId: 101, score: 8, delib: { score: 8, max: 15 } }, { stationId: 102, score: 4 }], 1),
    res(2, 'Bob', [{ stationId: 101, score: 15, delib: { score: 15, max: 15 } }, { stationId: 102, score: 9 }], 1),
    // Carla : verrouillée sur 101 seulement, jamais passée en 102 → verdict impossible (#297).
    res(3, 'Carla', [{ stationId: 101, score: 12, delib: { score: 12, max: 15 } }], 1),
  ];

  it('version servie → classement, moyenne /20 et mention sous le barème délibéré ; l’origine reste la trace', () => {
    const { c, el } = build(V1);
    expect(c.delibereServi()).toBeTrue();
    expect(c.lectureIncoherente()).toBeFalse();
    const bob = row(c, 'Bob');
    const alice = row(c, 'Alice');
    expect(bob.lecture).toBe('DELIBERE');
    // dénominateur PAR ÉTUDIANT : 15 + 10 = 25
    expect(bob.totalMax).toBe(25);
    expect(bob.moyenne20).toBeCloseTo((24 / 25) * 20, 5);   // 19.2
    expect(alice.moyenne20).toBeCloseTo((12 / 25) * 20, 5); // 9.6
    expect(bob.mention).toBe('Très bien');
    expect(alice.mention).toBe('Insuffisant');
    expect(bob.rang).toBe(1);
    expect(alice.rang).toBe(2);
    // la trace, intacte
    expect(bob.moyenne20Origine).toBeCloseTo(16, 5);
    expect(alice.moyenne20Origine).toBeCloseTo(8, 5);
    expect(bob.totalOrigine).toBe(24);
    expect(bob.totalMaxOrigine).toBe(30);
    // écran : badge explicite, colonne Origine, plus de « Délibéré /20 »
    const t = el.textContent ?? '';
    expect(t).toContain('Barème de délibération v1 appliqué — classement, moyenne et mention sous ce barème');
    expect(ths(el)).toContain('Origine /20');
    expect(ths(el)).not.toContain('Délibéré /20');
    expect(t).toContain('barème de délibération v1');
  });

  it('la garde #297 vaut pour la lecture délibérée : non verrouillé partout → rang 0, moyenne nulle', () => {
    const { c } = build(V1);
    const carla = row(c, 'Carla');
    expect(carla.lecture).toBe('DELIBERE');
    expect(carla.moyenne20).toBeNull();
    expect(carla.rang).toBe(0);
    expect(carla.mention).toContain('Incomplet');
  });

  it('le classement se RÉORDONNE sous le barème délibéré (le sens même de la fonctionnalité)', () => {
    // Origine : Dan 20/20 + 2/10 = 22/30 (14.67) devant Eve 14/20 + 6/10 = 20/30 (13.33).
    // v1 retire la station 101 : Dan 2/10 (4.0) passe DERRIÈRE Eve 6/10 (12.0).
    const rows = [
      res(1, 'Dan', [{ stationId: 101, score: 20, delib: null }, { stationId: 102, score: 2 }], 1),
      res(2, 'Eve', [{ stationId: 101, score: 14, delib: null }, { stationId: 102, score: 6 }], 1),
    ];
    const { c } = build(rows);
    expect(row(c, 'Eve').rang).toBe(1);
    expect(row(c, 'Dan').rang).toBe(2);
    expect(row(c, 'Eve').moyenne20).toBeCloseTo(12, 5);
    expect(row(c, 'Dan').moyenne20).toBeCloseTo(4, 5);
    // et l'origine dit l'inverse — la trace ne ment pas
    expect(row(c, 'Dan').moyenne20Origine).toBeGreaterThan(row(c, 'Eve').moyenne20Origine as number);
  });

  it('station EXCLUE : hors des deux sommes, jamais un échec, dite « exclue » dans la cellule et sur la carte', () => {
    const rows = [
      res(1, 'Dan', [{ stationId: 101, score: 3, delib: null }, { stationId: 102, score: 8 }], 1),
    ];
    const { c, el } = build(rows);
    const dan = row(c, 'Dan');
    expect(dan.stationsExclues.has(101)).toBeTrue();
    expect(dan.totalMax).toBe(10);
    expect(dan.total).toBe(8);
    expect(dan.moyenne20).toBeCloseTo(16, 5);
    const col101 = c.stationCols().find((s) => s.stationId === 101)!;
    expect(c.isStationFail(dan, col101)).toBeFalse();   // 3/20 serait un échec au barème d'origine
    expect(c.delibereCellule(dan, col101)).toBe('exclue');
    expect(c.stationsExcluesExam().has(101)).toBeTrue();
    expect(el.textContent).toContain('exclue du barème v1');
    expect(c.csvContenu()).toContain('barème de délibération v1');
  });

  it('cellule : la note saisie reste la cellule, la lecture délibérée est dite à côté seulement si elle diffère', () => {
    const { c } = build(V1);
    const bob = row(c, 'Bob');
    const col101 = c.stationCols().find((s) => s.stationId === 101)!;
    const col102 = c.stationCols().find((s) => s.stationId === 102)!;
    expect(c.delibereCellule(bob, col101)).toBe('15 / 15');  // max 20 → 15
    expect(c.delibereCellule(bob, col102)).toBeNull();       // identique : rien à dire
  });

  it('cartes KPI : moyenne promo, taux de réussite et min·max sous la lecture effective, barème nommé', () => {
    const { c, el } = build(V1);
    expect(c.moyennePromo()).toBeCloseTo((19.2 + 9.6) / 2, 5);
    expect(c.tauxReussite()).toBeCloseTo(50, 5);
    expect(c.minMaxLabel()).toBe('9.6 · 19.2');
    expect(el.querySelector('[data-testid="kpi-bareme"]')?.textContent?.trim()).toBe('barème de délibération v1');
  });

  it('CSV : Rang/Total/Moyenne/Mention effectifs, colonne Bareme, colonnes origine — le fichier s’explique seul', () => {
    const { c } = build(V1);
    const lignes = c.csvContenu().split('\r\n');
    // les en-têtes de station portent une virgule → cellules CSV citées
    expect(lignes[0]).toBe(
      'Rang,Nom,Numero,Echantillon,"Chimie analytique (/20, saisie)","Botanique (/10, saisie)",Total,Max,Moyenne/20,Mention,Bareme,Total origine,Moyenne origine/20,Etat',
    );
    expect(lignes[1]).toBe('1,P Bob,N2,,15,9,24,25,19.20,Très bien,barème de délibération v1,24,16.00,Complet');
    expect(lignes[2]).toBe('2,P Alice,N1,,8,4,12,25,9.60,Insuffisant,barème de délibération v1,12,8.00,Complet');
    expect(lignes[3]).toBe(',P Carla,N3,,12,,12,15,,Incomplet (1/2),barème de délibération v1,12,,Incomplet');
  });

  it('sans version → lecture d’origine, strictement comme avant (rang, moyenne, mention, CSV)', () => {
    const rows = [
      res(1, 'Alice', [{ stationId: 101, score: 8 }, { stationId: 102, score: 4 }], null),
      res(2, 'Bob', [{ stationId: 101, score: 15 }, { stationId: 102, score: 9 }], null),
    ];
    const { c, el } = build(rows);
    expect(c.delibereServi()).toBeFalse();
    expect(row(c, 'Bob').lecture).toBe('ORIGINE');
    expect(row(c, 'Bob').moyenne20).toBeCloseTo(16, 5);
    expect(row(c, 'Bob').rang).toBe(1);
    expect(row(c, 'Bob').mention).toBe('Très bien');
    expect(ths(el)).not.toContain('Origine /20');
    expect(c.lectureLabel()).toBe("barème d'origine");
    expect(c.csvContenu().split('\r\n')[1]).toBe("1,P Bob,N2,,15,9,24,30,16.00,Très bien,barème d'origine,24,16.00,Complet");
  });

  it('version présente mais NON servie (#399, couverture incomplète) → lecture d’origine + pastille rouge', () => {
    const rows = [
      res(1, 'Alice', [{ stationId: 101, score: 8 }, { stationId: 102, score: 4 }], 1, null),
      res(2, 'Bob', [{ stationId: 101, score: 15 }, { stationId: 102, score: 9 }], 1, null),
    ].map((r) => ({ ...r, denominateurDelibere: null }));
    const { c, el } = build(rows);
    expect(c.delibereServi()).toBeFalse();
    expect(row(c, 'Bob').lecture).toBe('ORIGINE');
    expect(row(c, 'Bob').rang).toBe(1);
    expect(el.textContent).toContain('lecture délibérée non servie');
  });

  it('invariant : Σ scoreDelibere ≠ totalDelibere servi → JAMAIS classé dessus, origine + pastille, console.error', () => {
    spyOn(console, 'error');
    const rows = [
      res(1, 'Alice', [{ stationId: 101, score: 8, delib: { score: 8, max: 15 } }, { stationId: 102, score: 4 }], 1, 99),
      res(2, 'Bob', [{ stationId: 101, score: 15, delib: { score: 15, max: 15 } }, { stationId: 102, score: 9 }], 1),
    ];
    const { c, el } = build(rows);
    expect(c.lectureIncoherente()).toBeTrue();
    expect(c.delibereServi()).toBeFalse();
    expect(row(c, 'Bob').lecture).toBe('ORIGINE');
    expect(row(c, 'Bob').moyenne20).toBeCloseTo(16, 5);
    expect(el.textContent).toContain('lecture délibérée incohérente — classement au barème d\'origine');
    expect(console.error).toHaveBeenCalled();
  });
});
