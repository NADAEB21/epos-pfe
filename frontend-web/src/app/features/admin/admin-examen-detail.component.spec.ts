import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminExamenDetailComponent } from './admin-examen-detail.component';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { BaremeDeliberation, ExamenResponse, ExamenResult } from '../../core/api/models';

/**
 * #390 — détail d'un examen en LECTURE SEULE pour le SUPER_ADMIN (ADR-0018 D5).
 *
 * <p>Épinglé : les quatre lectures (définition, stations, résultats, décision
 * de délibération) rendent chacune leur contenu ; une panne de scoring ne
 * cache pas la définition et se DIT ; aucun contrôle d'écriture (réajustement,
 * barème, lancement) et aucun lien vers le workspace du responsable.
 *
 * <p>Épinglé depuis la relecture du 2026-09-10 : un total ne s'affiche JAMAIS
 * nu. « 22,9 » se lisait comme une note sur 20 alors que c'était 22,9 / 40
 * après exclusion d'une station — l'administration voyait des « notes au-dessus
 * de 20 » et ignorait la décision du jury. Le résumé et le tableau lisent en
 * /20 avec le dénominateur servi, et la décision (opérations, motif, date)
 * est dite en toutes lettres.
 */
describe('AdminExamenDetailComponent — lecture seule (#390)', () => {
  const examApi = { getExamen: jasmine.createSpy('getExamen'), listStations: jasmine.createSpy('listStations') };
  const scoring = {
    getExamenResults: jasmine.createSpy('getExamenResults'),
    listBaremesDeliberation: jasmine.createSpy('listBaremesDeliberation'),
  };
  const directory = { listMatieres: jasmine.createSpy('listMatieres') };

  const EXAM: ExamenResponse = {
    id: 92, nom: 'IA-F1 — Cohorte de référence', matiereId: 1, dateExamen: '2026-06-20', heureDebut: '09:00',
    dureeStationMin: 12, nbEtudiantsParStation: 6, statut: 'TERMINE', description: 'Cohorte plantée',
    hasPdfSujet: false, pdfSujetNom: null, createdAt: null, updatedAt: null,
  };
  /** Deux étudiants, trois stations /20 → dénominateur d'origine 60. */
  const RESULTS: ExamenResult[] = [
    { participationId: 1, etudiantId: 500, numeroInscription: 'REF-0001', nom: 'Khelifi', prenom: 'Maryem',
      numEchantillon: null, totalScore: 28.5, stationsNotees: 3, stations: [], denominateurOriginal: 60 },
    { participationId: 2, etudiantId: 501, numeroInscription: 'REF-0002', nom: 'Trabelsi', prenom: 'Wael',
      numEchantillon: null, totalScore: 12, stationsNotees: 3, stations: [], denominateurOriginal: 60 },
  ];
  /** La station 125 exclue par le jury : dénominateur délibéré 40. */
  const BAREME_V1: BaremeDeliberation = {
    id: 13, examenId: 92, version: 1, motif: 'Difficulté de la station au-dessus du niveau de la promotion',
    creePar: 2, createdAt: '2026-09-05T22:38:11',
    operations: [{ type: 'EXCLURE_STATION', cibleItemId: null, cibleStationId: 125, nouvelleEchelle: null }],
  };
  const RESULTS_DELIBERES: ExamenResult[] = [
    { ...RESULTS[0], totalDelibere: 10, denominateurDelibere: 40, baremeVersion: 1 },   // brut 28.5/60 → délibéré 10/40 = 5 /20
    { ...RESULTS[1], totalDelibere: 12, denominateurDelibere: 40, baremeVersion: 1 },   // brut 12/60   → délibéré 12/40 = 6 /20
  ];

  function build(opts: {
    scoringDown?: boolean; examDown?: boolean; results?: ExamenResult[]; baremes?: BaremeDeliberation[]; baremesDown?: boolean;
  } = {}) {
    examApi.getExamen.and.returnValue(opts.examDown ? throwError(() => ({ status: 404 })) : of(EXAM));
    examApi.listStations.and.returnValue(of([
      { id: 124, nom: 'Station Défauts', ordre: 1, type: 'PRATIQUE', hasGrille: true, evaluateurIds: [74] },
      { id: 125, nom: 'Station Témoin', ordre: 2, type: 'PRATIQUE', hasGrille: true, evaluateurIds: [75] },
    ]));
    scoring.getExamenResults.and.returnValue(
      opts.scoringDown ? throwError(() => ({ status: 503 })) : of(opts.results ?? RESULTS),
    );
    scoring.listBaremesDeliberation.and.returnValue(
      opts.scoringDown || opts.baremesDown ? throwError(() => ({ status: 503 })) : of(opts.baremes ?? []),
    );
    directory.listMatieres.and.returnValue(of([{ id: 1, code: 'CT', libelle: 'Chimie thérapeutique', active: true }]));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AdminExamenDetailComponent],
      providers: [
        provideRouter([]),
        { provide: ExamApiService, useValue: examApi },
        { provide: ScoringApiService, useValue: scoring },
        { provide: DirectoryApiService, useValue: directory },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: '92' }) } } },
      ],
    });
    const fixture = TestBed.createComponent(AdminExamenDetailComponent);
    fixture.detectChanges();
    return { cmp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  it('rend la définition, les stations et les résultats consolidés lus /20 avec leur dénominateur', () => {
    const { cmp, el } = build();

    expect(cmp.examenId).toBe(92);
    expect(el.textContent).toContain('IA-F1 — Cohorte de référence');
    expect(el.textContent).toContain('Chimie thérapeutique');
    expect(el.textContent).toContain('Station Défauts');
    expect(el.textContent).toContain('Maryem Khelifi');
    expect(el.textContent).toContain('REF-0002');
    expect(cmp.resume()).toEqual({
      n: 2, moyenne: 20.25, max: 28.5, min: 12, denominateur: 60,
      n20: 2, moyenne20: 6.75, min20: 4, max20: 9.5,
    });
    // Le résumé dit « /20 » ET le total avec son dénominateur — jamais « 20,25 » tout seul.
    // (TestBed rend en locale par défaut ; l'app enregistre fr-FR — le séparateur est donc toléré.)
    const resume = el.querySelector('[data-testid="resume-resultats"]')?.textContent ?? '';
    expect(resume).toMatch(/6[.,][78]\s*\/20/);
    expect(resume).toMatch(/total moyen 20[.,][23] \/ 60/);
    expect(el.textContent).toContain('Lecture seule');
    // Pas de décision de jury sans version servie.
    expect(el.querySelector('[data-testid="decision-jury"]')).toBeNull();
  });

  it("aucun contrôle d'écriture ni lien vers le workspace (ADR-0018 D5)", () => {
    const { el } = build();
    const texte = (el.textContent ?? '').toLowerCase();

    expect(texte).not.toContain('réajust');
    expect(texte).not.toContain('barème de délibération');
    expect(texte).not.toContain('lancer');
    expect(el.querySelectorAll('button').length).toBe(0);
    const hrefs = Array.from(el.querySelectorAll('a')).map((a) => a.getAttribute('href') ?? '');
    for (const h of hrefs) expect(h.startsWith('/admin/examens')).toBeTrue();
  });

  it('scoring en panne : les résultats le DISENT, la définition reste servie', () => {
    const { cmp, el } = build({ scoringDown: true });

    expect(cmp.resultats().statut).toBe('indisponible');
    expect(el.textContent).toContain('Résultats indisponibles');
    expect(el.textContent).toContain('IA-F1 — Cohorte de référence');
    expect(el.textContent).toContain('Station Témoin');
  });

  it('examen inconnu / exam-service en panne : titre de repli et panne dite', () => {
    const { cmp, el } = build({ examDown: true });

    expect(cmp.titre()).toBe('Examen 92');
    expect(el.textContent).toContain("Définition de l'examen indisponible");
  });

  it('#401 : version servie → lecture /20 au barème délibéré, trié dessus, brut à côté, et la DÉCISION dite', () => {
    const { cmp, el } = build({ results: RESULTS_DELIBERES, baremes: [BAREME_V1] });

    expect(cmp.baremeVersion()).toBe(1);
    expect(cmp.lignes().map((x) => x.nom)).toEqual(['Trabelsi', 'Khelifi']);   // réordonné sur le délibéré
    expect(cmp.resume()).toEqual({
      n: 2, moyenne: 11, max: 12, min: 10, denominateur: 40,
      n20: 2, moyenne20: 5.5, min20: 5, max20: 6,
    });
    const resume = el.querySelector('[data-testid="resume-resultats"]')?.textContent ?? '';
    expect(resume).toContain('5.5');
    expect(resume).toContain('total moyen 11.0 / 40');
    expect(el.textContent).toContain('/20 (barème de délibération v1)');
    expect(el.textContent).toContain('Total brut');
    expect(el.textContent).toContain('28.5 / 60');   // la trace garde son propre dénominateur

    // La décision du jury, en toutes lettres : quoi, pourquoi, quand.
    const decision = el.querySelector('[data-testid="decision-jury"]')?.textContent ?? '';
    expect(decision).toContain('Décision du jury — barème v1');
    expect(decision).toContain('Exclure la station « Station Témoin » du barème');
    expect(decision).toContain('Difficulté de la station au-dessus du niveau de la promotion');
    expect(decision).toContain('5 septembre 2026');
    // Toujours en lecture seule : la décision est dite, pas modifiable.
    expect(el.querySelectorAll('button').length).toBe(0);
  });

  it("#401 : version servie mais historique du barème en panne → la décision est dite indisponible, pas cachée", () => {
    const { cmp, el } = build({ results: RESULTS_DELIBERES, baremesDown: true });

    expect(cmp.baremeVersion()).toBe(1);
    expect(cmp.decision()).toBeNull();
    const decision = el.querySelector('[data-testid="decision-jury"]')?.textContent ?? '';
    expect(decision).toContain('Décision du jury — barème v1');
    expect(decision).toContain('indisponible');
  });

  it('dénominateur non servi (résultats pré-barème figé) : totaux en points, jamais déguisés en /20', () => {
    const sansDenominateur = RESULTS.map((r) => ({ ...r, denominateurOriginal: null }));
    const { cmp, el } = build({ results: sansDenominateur });

    expect(cmp.resume()?.moyenne20).toBeNull();
    expect(cmp.resume()?.denominateur).toBeNull();
    const resume = el.querySelector('[data-testid="resume-resultats"]')?.textContent ?? '';
    expect(resume).toContain('dénominateur non servi');
    expect(resume).not.toContain('/20');
  });

  it('un 0 /20 est une note, pas une absence', () => {
    const zero = [{ ...RESULTS[0], totalScore: 0 }];
    const { cmp, el } = build({ results: zero });

    expect(cmp.note20(zero[0])).toBe(0);
    const cellules = Array.from(el.querySelectorAll('tbody td')).map((td) => td.textContent?.trim() ?? '');
    expect(cellules).toContain('0.0');
  });
});
