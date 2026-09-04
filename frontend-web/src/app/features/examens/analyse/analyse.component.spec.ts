import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AnalyseComponent } from './analyse.component';
import { AiApiService } from '../../../core/api/ai-api.service';
import { DirectoryApiService } from '../../../core/api/directory-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { EvaluateursExamen, ExamenResponse, IndiceAi, IndicesExamen, UserResponse } from '../../../core/api/models';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

/**
 * #407 — l'onglet « Analyse » : les indices se lisent en phrases (F4), station par
 * station ; les évaluateurs sont comparés INTRA-station avec leur refus nommé ;
 * les deux appels dégradent séparément ; la cohorte de référence est annoncée.
 */
describe('AnalyseComponent — l’analyse lisible (#407)', () => {
  let ai: jasmine.SpyObj<AiApiService>;
  let examApi: jasmine.SpyObj<ExamApiService>;
  let directory: jasmine.SpyObj<DirectoryApiService>;

  const EXAM: ExamenResponse = {
    id: 92, nom: 'IA-F1 — Cohorte de référence (défauts plantés)', matiereId: 1, dateExamen: '2026-06-20', heureDebut: '09:00',
    dureeStationMin: 12, nbEtudiantsParStation: 6, statut: 'TERMINE', description: null, hasPdfSujet: false, pdfSujetNom: null,
    createdAt: null, updatedAt: null,
  };
  const ok = (code: string, valeur: number, n = 36, details: Record<string, unknown> = {}): IndiceAi =>
    ({ code, statut: 'CONCLUANT', n, valeur, ic: [valeur - 0.1, valeur + 0.1], raison: null, details });
  const refus = (code: string, raison: string, n = 4): IndiceAi =>
    ({ code, statut: 'NON_CONCLUANT', n, valeur: null, ic: null, raison, details: {} });

  function indices(): IndicesExamen {
    return {
      examen_id: 92, entrees_hash: 'abc', moteur_version: 'n8',
      exclusions: { saisi_par_null: 0, detail_incomplet: 0, notations_analysees: 108, sans_aucun_item: 0 },
      par_critere: [
        { item_id: 280, libelle: 'Critère impossible', type: 'BINAIRE', grille_id: 111, station_id: 124, difficulte: ok('DIFFICULTE', 0.05), discrimination: refus('DISCRIMINATION', 'non concluant — variance nulle (toutes les notes identiques)') },
        { item_id: 279, libelle: 'Geste conforme', type: 'BINAIRE', grille_id: 111, station_id: 124, difficulte: ok('DIFFICULTE', 0.6), discrimination: ok('DISCRIMINATION', 0.35) },
      ],
      par_grille: [{ grille_id: 111, station_id: 124, alpha_cronbach: ok('ALPHA_CRONBACH', 0.06) }],
      par_station: [{ station_id: 124, concentration_echec: ok('CONCENTRATION_ECHEC', 0.72, 36, { p_value: 0.0001 }) }],
    };
  }
  function evaluateurs(): EvaluateursExamen {
    return {
      examen_id: 92, entrees_hash: 'abc', moteur_version: 'n8',
      exclusions: { saisi_par_null: 0, detail_incomplet: 0, notations_analysees: 108, sans_aucun_item: 0 },
      par_station: [
        { station_id: 124, nb_evaluateurs: 1, evaluateurs: [{ evaluateur_id: 74, n: 36, severite: refus('SEVERITE_EVALUATEUR', 'comparaison non concluante — un seul évaluateur a noté cette station', 36) }] },
        { station_id: 125, nb_evaluateurs: 2, evaluateurs: [{ evaluateur_id: 75, n: 18, severite: ok('SEVERITE_EVALUATEUR', 4.6, 18) }, { evaluateur_id: 76, n: 18, severite: ok('SEVERITE_EVALUATEUR', -4.6, 18) }] },
      ],
    };
  }

  function build(opts: { indicesDown?: boolean; indicesRefus?: string; evalDown?: boolean; nom?: string } = {}) {
    ai = jasmine.createSpyObj('AiApiService', ['getIndices', 'getEvaluateurs']);
    ai.getIndices.and.returnValue(
      opts.indicesRefus ? throwError(() => ({ status: 409, error: { message: opts.indicesRefus } }))
        : opts.indicesDown ? throwError(() => ({ status: 503 })) : of(indices()),
    );
    ai.getEvaluateurs.and.returnValue(opts.evalDown ? throwError(() => ({ status: 503 })) : of(evaluateurs()));
    examApi = jasmine.createSpyObj('ExamApiService', ['listStations']);
    examApi.listStations.and.returnValue(of([{ id: 124, nom: 'Station Défauts', ordre: 1 }, { id: 125, nom: 'Station Sévérité', ordre: 2 }]));
    directory = jasmine.createSpyObj('DirectoryApiService', ['listUsers']);
    directory.listUsers.and.returnValue(of([
      { id: 74, email: 'a@epos.tn', nom: 'Ben Ali', prenom: 'Aymen', isActive: true, createdAt: null },
      { id: 75, email: 'b@epos.tn', nom: 'Trabelsi', prenom: 'Sonia', isActive: true, createdAt: null },
    ] as unknown as UserResponse[]));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AnalyseComponent],
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(), ExamenWorkspaceStore,
        { provide: AiApiService, useValue: ai }, { provide: ExamApiService, useValue: examApi }, { provide: DirectoryApiService, useValue: directory },
      ],
    });
    TestBed.inject(ExamenWorkspaceStore).exam.set({ ...EXAM, nom: opts.nom ?? EXAM.nom });
    const fixture = TestBed.createComponent(AnalyseComponent);
    (fixture.componentInstance as unknown as { id: () => string }).id = () => '92';
    fixture.detectChanges();
    fixture.detectChanges();
    return { c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  it('lit chaque indice en PHRASE, station par station, et compte les lectures à regarder', () => {
    const { c, el } = build();
    const t = el.textContent ?? '';
    expect(c.parStation().length).toBe(1);
    expect(c.parStation()[0].nom).toBe('Station Défauts');
    expect(t).toContain('Cohérence interne');
    expect(t).toContain('Critère impossible');
    expect(t).toContain('Geste conforme');
    // α 0,06 + concentration p<0,05 + critère impossible (p=0,05) → 3 lectures à regarder
    expect(c.parStation()[0].alertes).toBe(3);
    expect(el.querySelector('[data-testid="alertes"]')?.textContent).toContain('3 lectures à regarder');
    expect(el.querySelectorAll('app-lecture-indice').length).toBeGreaterThanOrEqual(2);
  });

  it('un refus du moteur se lit VERBATIM, en italique ambre, jamais une valeur', () => {
    const { el } = build();
    const cell = Array.from(el.querySelectorAll('td')).find((td) => td.textContent?.includes('variance nulle'));
    expect(cell).toBeDefined();
    expect(cell!.className).toContain('text-amber-800');
  });

  it('les évaluateurs : noms résolus par l’annuaire, refus nommé sur station à évaluateur unique, jamais un classement', () => {
    const { c, el } = build();
    const t = el.textContent ?? '';
    expect(c.evaluateursParStation().length).toBe(2);
    expect(t).toContain('Aymen Ben Ali');
    expect(t).toContain('Sonia Trabelsi');
    expect(t).toContain('Évaluateur n° 76');            // inconnu de l'annuaire : id, pas une invention
    expect(t).toContain('un seul évaluateur a noté cette station');
    expect(t).toContain('jamais un classement');
  });

  it('cohorte de référence (nom IA-F1) → l’encart « trois défauts plantés » ; sinon rien', () => {
    expect(build().el.textContent).toContain('Trois défauts y ont été plantés');
    expect(build({ nom: 'Examen pratique de chimie' }).el.textContent).not.toContain('plantés');
  });

  it('module IA absent → bandeau nominatif ; les évaluateurs se chargent à part', () => {
    const { c, el } = build({ indicesDown: true });
    expect(c.indicesEtat()).toBe('absents');
    expect(el.textContent).toContain('Analyse indisponible');
    expect(c.evaluateursEtat()).toBe('prets');
  });

  it('refus 409 (examen non clos) → message serveur verbatim', () => {
    const { el } = build({ indicesRefus: 'Les indices ne se calculent que sur un examen clos — statut actuel : EN_COURS.' });
    expect(el.textContent).toContain('statut actuel : EN_COURS');
  });

  it('évaluateurs indisponibles → note discrète, l’analyse des stations reste', () => {
    const { c, el } = build({ evalDown: true });
    expect(c.evaluateursEtat()).toBe('absents');
    expect(el.textContent).toContain('Analyse des évaluateurs non disponible');
    expect(c.parStation().length).toBe(1);
  });

  it('un code d’indice inconnu dégrade la cellule sans casser l’onglet', () => {
    const { c } = build();
    const inconnu = { code: 'NOUVEAU_2027', statut: 'CONCLUANT', n: 10, valeur: 0.5, ic: null, raison: null, details: {} } as never;
    expect(c.phrase(inconnu).texte).toContain('lecture indisponible');
    expect(c.phrase(inconnu).refus).toBeTrue();
  });
});
