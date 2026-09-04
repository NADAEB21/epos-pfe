import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideEchartsCore } from 'ngx-echarts';
import { of, throwError } from 'rxjs';
import { TendancesComponent } from './tendances.component';
import { AiApiService } from '../../core/api/ai-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ExamenTendanceAi, TendancesMatiere } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth.store';
import { loadEcharts } from '../../shared/graphes/echarts-setup';

/**
 * #365 (N10) — Tendances d'une matière.
 *
 * <p>Épinglé : la matière vient du périmètre du responsable (ou de la route
 * admin), les sessions closes se rendent dans l'ordre du backend avec la
 * dernière sélectionnée par défaut, le barème délibéré est dit, les refus et
 * pannes du module IA se voient (jamais un écran vide), un code de lecture
 * inconnu dégrade sans casser, et les noms de station se replient sur
 * « Station n ».
 */
describe('TendancesComponent — BI matière (#365)', () => {
  const ai = { getTendances: jasmine.createSpy('getTendances') };
  const directory = { listMatieres: jasmine.createSpy('listMatieres') };
  const examApi = { listStations: jasmine.createSpy('listStations') };

  const session = (over: Partial<ExamenTendanceAi>): ExamenTendanceAi => ({
    examen_id: 1, nom: 'Session', date_examen: '2026-01-15', statut: 'TERMINE',
    entrees_hash: 'abcdef0123456789', moteur_version: 'n8', n_notations_verrouillees: 36,
    couverture_snapshot_complete: true,
    origine: { n_etudiants: 36, denominateur: 55, mediane: 28.5, moyenne: 29.6, taux_reussite: 0.5556 },
    delibere: null, bareme_version: null,
    lecture: { n_etudiants: 36, denominateur: 55, mediane: 28.5, moyenne: 29.6, taux_reussite: 0.5556 },
    lecture_officielle: 'ORIGINE', stations_exclues: [],
    bins: [
      { label: '0–11', count: 2, pct: 5.6, sousSeuil: true },
      { label: '11–22', count: 8, pct: 22.2, sousSeuil: true },
      { label: '22–33', count: 14, pct: 38.9, sousSeuil: false },
      { label: '33–44', count: 9, pct: 25, sousSeuil: false },
      { label: '44–55', count: 3, pct: 8.3, sousSeuil: false },
    ],
    par_station: [
      { station_id: 107, n: 36, echecs: 26, taux_echec: 0.72, mediane: 6, note_max: 15 },
      { station_id: 108, n: 36, echecs: 15, taux_echec: 0.42, mediane: 10, note_max: 20 },
    ],
    exclusions: { saisi_par_null: 0, detail_incomplet: 0, notations_analysees: 36, sans_aucun_item: 0 },
    lectures: [],
    ...over,
  });

  const tendances = (examens: ExamenTendanceAi[], over: Partial<TendancesMatiere> = {}): TendancesMatiere => ({
    matiere_id: 1, examens, exclusions: { non_clos: 1, sans_snapshot: 0, hors_snapshot: 0 }, lectures: [], ...over,
  });

  function build(opts: {
    data?: TendancesMatiere;
    error?: { status: number; error?: { message?: string } };
    matiereIds?: number[];
    routeMatiere?: string;
    stationsOk?: boolean;
  } = {}) {
    ai.getTendances.and.returnValue(opts.error ? throwError(() => opts.error) : of(opts.data ?? tendances([])));
    directory.listMatieres.and.returnValue(
      of([{ id: 1, code: 'CT', libelle: 'Chimie thérapeutique', active: true }]),
    );
    examApi.listStations.and.returnValue(
      opts.stationsOk === false
        ? throwError(() => ({ status: 503 }))
        : of([{ id: 107, nom: 'Station Défauts' }, { id: 108, nom: 'Station Témoin' }]),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [TendancesComponent],
      providers: [
        provideRouter([]),
        provideEchartsCore({ echarts: loadEcharts }),
        { provide: AiApiService, useValue: ai },
        { provide: DirectoryApiService, useValue: directory },
        { provide: ExamApiService, useValue: examApi },
      ],
    });
    TestBed.inject(AuthStore).setUser({
      email: 'resp@epos.tn', userId: 2, accessTokenExpiresAt: new Date(Date.now() + 3600_000),
      authorities: (opts.matiereIds ?? [1]).map((matiereId) => ({ role: 'RESPONSABLE_MATIERE' as const, matiereId })),
    });
    const fixture = TestBed.createComponent(TendancesComponent);
    if (opts.routeMatiere !== undefined) fixture.componentRef.setInput('matiereId', opts.routeMatiere);
    fixture.detectChanges();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  const DEUX = [
    session({ examen_id: 80, nom: 'Rattrapage', date_examen: '2026-01-15' }),
    session({ examen_id: 92, nom: 'Jumeau F1', date_examen: '2026-06-10', bareme_version: 1,
              delibere: { n_etudiants: 36, denominateur: 40, mediane: 24, moyenne: 25, taux_reussite: 0.75 },
              lecture: { n_etudiants: 36, denominateur: 40, mediane: 24, moyenne: 25, taux_reussite: 0.75 },
              lecture_officielle: 'DELIBERE' }),
  ];

  it('lit la matière du périmètre et rend les sessions closes, la dernière sélectionnée', () => {
    const { c, el } = build({ data: tendances(DEUX) });
    expect(ai.getTendances).toHaveBeenCalledWith(1);
    expect(el.textContent).toContain('Tendances — Chimie thérapeutique');
    expect(el.textContent).toContain('Sessions closes (2)');
    expect(c.lignesSessions().map((l) => l.id)).toEqual([80, 92]);
    expect(c.selected()?.examen_id).toBe(92);
    expect(el.textContent).toContain('Jumeau F1');
    expect(el.textContent).toContain('1 session(s) non close(s)');
  });

  it('dit le barème de délibération appliqué (avant → après, en /20)', () => {
    const { el } = build({ data: tendances(DEUX) });
    const ligne = el.querySelector('[data-testid="ligne-delibere"]')?.textContent ?? '';
    expect(ligne).toContain('Barème de délibération v1 appliqué');
    expect(ligne).toContain('→');
    expect(ligne).toContain('12 /20');   // 24/40 → 12
  });

  it('résout les noms de station, avec repli « Station n » quand exam-service se tait', () => {
    const ok = build({ data: tendances(DEUX) });
    expect(ok.c.lignesStations().map((l) => l.label)).toEqual(['Station Défauts', 'Station Témoin']);
    const ko = build({ data: tendances(DEUX), stationsOk: false });
    expect(ko.c.lignesStations().map((l) => l.label)).toEqual(['Station 107', 'Station 108']);
    expect(ko.c.lignesStations()[0].valeurPct).toBeCloseTo(72, 5);
  });

  it('cliquer une session la sélectionne', () => {
    const { c } = build({ data: tendances(DEUX) });
    c.selectExamen(80);
    expect(c.selected()?.nom).toBe('Rattrapage');
    expect(examApi.listStations).toHaveBeenCalledWith(80);
  });

  it('aucune session close → la lecture backend, verbatim, jamais un graphe vide', () => {
    const { el } = build({
      data: tendances([], { lectures: [{ code: 'AUCUN_EXAMEN_CLOS', raison: 'aucune session close pour cette matière — les tendances n’existent qu’à l’usage' }] }),
    });
    expect(el.textContent).toContain('Aucune session close');
    expect(el.textContent).toContain('les tendances n’existent qu’à l’usage');
    expect(el.querySelector('app-barres-concentration')).toBeNull();
  });

  it('un code de lecture inconnu dégrade visiblement au lieu de casser', () => {
    const { el } = build({
      data: tendances([session({ lectures: [{ code: 'NOUVEAU_CODE_2027', raison: 'raison serveur' }] })]),
    });
    expect(el.textContent).toContain('lecture indisponible');
    expect(el.textContent).toContain('raison serveur');
  });

  it('module IA injoignable → bandeau ambre nominatif', () => {
    const { el } = build({ error: { status: 503 } });
    expect(el.textContent).toContain('Tendances indisponibles — module IA injoignable.');
  });

  it('refus nominatif du serveur (403) → affiché verbatim', () => {
    const { el } = build({ error: { status: 403, error: { message: 'Accès refusé : la matière 4 est hors de votre périmètre.' } } });
    expect(el.textContent).toContain('la matière 4 est hors de votre périmètre');
  });

  it('route admin : la matière vient de la route, lecture seule, sans lien vers le workspace', () => {
    const { el } = build({ data: tendances(DEUX), routeMatiere: '4', matiereIds: [] });
    expect(ai.getTendances).toHaveBeenCalledWith(4);
    expect(el.textContent).toContain('Lecture seule');
    expect(el.querySelector('a[href^="/examens/"]')).toBeNull();
  });

  it('plusieurs matières → un sélecteur, et le changement recharge', () => {
    const { fixture, c, el } = build({ data: tendances(DEUX), matiereIds: [1, 2] });
    expect(el.querySelector('select')).not.toBeNull();
    c.onMatiereChange('2');
    fixture.detectChanges(); // le rechargement est porté par un effect : un tick
    expect(ai.getTendances).toHaveBeenCalledWith(2);
  });
  it('#401 : la barre d’une session délibérée porte la lecture EFFECTIVE (taux délibéré), l’origine reste dans la carte', () => {
    const { c, el } = build({ data: tendances(DEUX) });
    const l92 = c.lignesSessions().find((l) => l.id === 92)!;
    expect(l92.valeurPct).toBeCloseTo(75, 5);          // 0.75 délibéré, pas 0.5556 origine
    expect(l92.detail).toContain('barème v1');
    const l80 = c.lignesSessions().find((l) => l.id === 80)!;
    expect(l80.valeurPct).toBeCloseTo(55.56, 1);
    expect(el.textContent).toContain('Barème de délibération v1 appliqué');
  });
});
