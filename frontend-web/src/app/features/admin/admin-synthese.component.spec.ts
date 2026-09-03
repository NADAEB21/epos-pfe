import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideEchartsCore } from 'ngx-echarts';
import { of, throwError } from 'rxjs';
import { AdminSyntheseComponent } from './admin-synthese.component';
import { AiApiService } from '../../core/api/ai-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { MatiereSyntheseAi, SyntheseFaculte } from '../../core/api/models';
import { loadEcharts } from '../../shared/graphes/echarts-setup';

/**
 * #365 (N10) — Synthèse de la faculté, AGRÉGÉE D'ABORD (ADR-0021 D5).
 *
 * <p>Épinglé : les compteurs et la table par matière, le libellé résolu par le
 * catalogue (repli « Matière n »), le refus d'effectif rendu VERBATIM (jamais
 * un nombre nu), la panne du module IA dite, et — le point doctrinal — aucun
 * mot « étudiant » nominatif ni colonne par étudiant dans l'écran.
 */
describe('AdminSyntheseComponent — synthèse facultaire (#365)', () => {
  const ai = { getSynthese: jasmine.createSpy('getSynthese') };
  const directory = { listMatieres: jasmine.createSpy('listMatieres') };

  const matiere = (over: Partial<MatiereSyntheseAi>): MatiereSyntheseAi => ({
    matiere_id: 1, nb_examens_clos: 3, nb_avec_bareme_delibere: 1,
    dernier_examen: { examen_id: 93, nom: 'Jumeau F1', date_examen: '2026-09-02' },
    hors_snapshot: 0, n_etudiants: 101, mediane_sur_20: 10.4, taux_reussite: 0.56,
    statut: 'CONCLUANT', raison: null, sessions: [], ...over,
  });

  const synthese = (matieres: MatiereSyntheseAi[]): SyntheseFaculte => ({
    faculte: { nb_matieres: matieres.length, nb_examens_clos: 4, n_etudiants: 104,
               mediane_sur_20: 10.2, taux_reussite: 0.55, statut: 'CONCLUANT', raison: null },
    matieres,
    exclusions: { sans_snapshot: 0 },
  });

  function build(opts: { data?: SyntheseFaculte; error?: { status: number; error?: { message?: string } }; catalogueOk?: boolean } = {}) {
    ai.getSynthese.and.returnValue(opts.error ? throwError(() => opts.error) : of(opts.data ?? synthese([])));
    directory.listMatieres.and.returnValue(
      opts.catalogueOk === false
        ? throwError(() => ({ status: 503 }))
        : of([{ id: 1, code: 'CT', libelle: 'Chimie thérapeutique', active: true }]),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AdminSyntheseComponent],
      providers: [
        provideRouter([]),
        provideEchartsCore({ echarts: loadEcharts }),
        { provide: AiApiService, useValue: ai },
        { provide: DirectoryApiService, useValue: directory },
      ],
    });
    const fixture = TestBed.createComponent(AdminSyntheseComponent);
    fixture.detectChanges();
    fixture.detectChanges();
    return { c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  const DEUX = [
    matiere({ matiere_id: 1 }),
    matiere({ matiere_id: 4, nb_examens_clos: 1, nb_avec_bareme_delibere: 0, n_etudiants: 3,
              mediane_sur_20: null, taux_reussite: null, statut: 'NON_CONCLUANT',
              raison: 'non concluant — effectif insuffisant (n=3 < 10)',
              dernier_examen: { examen_id: 63, nom: 'Autre', date_examen: '2026-05-01' } }),
  ];

  it('rend les compteurs, la table par matière et les libellés du catalogue', () => {
    const { c, el } = build({ data: synthese(DEUX) });
    const t = el.textContent ?? '';
    expect(t).toContain('Agrégé d\'abord — aucune vue par étudiant');
    expect(t).toContain('Chimie thérapeutique');
    expect(t).toContain('Matière 4');
    expect(t).toContain('02/09/2026 · Jumeau F1');
    expect(c.lignesMatieres().map((l) => l.id)).toEqual([1, 4]);
    expect(c.lignesMatieres()[0].valeurPct).toBeCloseTo(56, 5);
    expect(el.querySelector('a[href="/admin/tendances/1"]')).not.toBeNull();
  });

  it('sous l\'effectif minimal : le refus VERBATIM, jamais un nombre nu', () => {
    const { c, el } = build({ data: synthese(DEUX) });
    expect(el.textContent).toContain('non concluant — effectif insuffisant (n=3 < 10)');
    expect(c.lignesMatieres()[1].valeurPct).toBeNull();
    expect(c.lignesMatieres()[1].detail).toContain('effectif insuffisant');
  });

  it('aucune colonne ni ligne par étudiant (ADR-0021 D5)', () => {
    const { el } = build({ data: synthese(DEUX) });
    const entetes = Array.from(el.querySelectorAll('th')).map((th) => th.textContent?.trim());
    expect(entetes).not.toContain('Étudiant');
    expect(entetes).toContain('Étudiants');   // un COMPTE, pas une liste
  });

  it('catalogue en panne → matières par numéro, écran intact', () => {
    const { el } = build({ data: synthese(DEUX), catalogueOk: false });
    expect(el.textContent).toContain('Catalogue des matières indisponible');
    expect(el.textContent).toContain('Matière 1');
  });

  it('module IA injoignable → bandeau ambre nominatif', () => {
    const { el } = build({ error: { status: 503 } });
    expect(el.textContent).toContain('Synthèse indisponible — module IA injoignable.');
  });

  it('refus nominatif (403) → affiché verbatim', () => {
    const { el } = build({ error: { status: 403, error: { message: 'Accès refusé : la synthèse facultaire est réservée au SUPER_ADMIN (ADR-0021 D5).' } } });
    expect(el.textContent).toContain('réservée au SUPER_ADMIN');
  });

  it('aucune session close dans la faculté → dit, jamais des zéros muets', () => {
    const { el } = build({ data: { ...synthese([]), faculte: { nb_matieres: 0, nb_examens_clos: 0, n_etudiants: 0, mediane_sur_20: null, taux_reussite: null, statut: 'NON_CONCLUANT', raison: 'non concluant — effectif insuffisant (n=0 < 10)' } } });
    expect(el.textContent).toContain('Aucune session close dans la faculté');
    expect(el.textContent).toContain('effectif insuffisant (n=0 < 10)');
  });
});
