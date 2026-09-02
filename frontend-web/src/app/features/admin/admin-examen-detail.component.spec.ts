import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminExamenDetailComponent } from './admin-examen-detail.component';
import { ExamApiService } from '../../core/api/exam-api.service';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamenResponse, ExamenResult } from '../../core/api/models';

/**
 * #390 — détail d'un examen en LECTURE SEULE pour le SUPER_ADMIN (ADR-0018 D5).
 *
 * <p>Épinglé : les trois lectures (définition, stations, résultats) rendent
 * chacune leur contenu ; une panne de scoring ne cache pas la définition et se
 * DIT ; aucun contrôle d'écriture (réajustement, barème, lancement) et aucun
 * lien vers le workspace du responsable.
 */
describe('AdminExamenDetailComponent — lecture seule (#390)', () => {
  const examApi = { getExamen: jasmine.createSpy('getExamen'), listStations: jasmine.createSpy('listStations') };
  const scoring = { getExamenResults: jasmine.createSpy('getExamenResults') };
  const directory = { listMatieres: jasmine.createSpy('listMatieres') };

  const EXAM: ExamenResponse = {
    id: 92, nom: 'IA-F1 — Cohorte de référence', matiereId: 1, dateExamen: '2026-06-20', heureDebut: '09:00',
    dureeStationMin: 12, nbEtudiantsParStation: 6, statut: 'TERMINE', description: 'Cohorte plantée',
    hasPdfSujet: false, pdfSujetNom: null, createdAt: null, updatedAt: null,
  };
  const RESULTS: ExamenResult[] = [
    { participationId: 1, etudiantId: 500, numeroInscription: 'REF-0001', nom: 'Khelifi', prenom: 'Maryem',
      numEchantillon: null, totalScore: 28.5, stationsNotees: 3, stations: [] },
    { participationId: 2, etudiantId: 501, numeroInscription: 'REF-0002', nom: 'Trabelsi', prenom: 'Wael',
      numEchantillon: null, totalScore: 12, stationsNotees: 3, stations: [] },
  ];

  function build(opts: { scoringDown?: boolean; examDown?: boolean } = {}) {
    examApi.getExamen.and.returnValue(opts.examDown ? throwError(() => ({ status: 404 })) : of(EXAM));
    examApi.listStations.and.returnValue(of([
      { id: 124, nom: 'Station Défauts', ordre: 1, type: 'PRATIQUE', hasGrille: true, evaluateurIds: [74] },
      { id: 125, nom: 'Station Témoin', ordre: 2, type: 'PRATIQUE', hasGrille: true, evaluateurIds: [75] },
    ]));
    scoring.getExamenResults.and.returnValue(opts.scoringDown ? throwError(() => ({ status: 503 })) : of(RESULTS));
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

  it('rend la définition, les stations et les résultats consolidés (totaux bruts)', () => {
    const { cmp, el } = build();

    expect(cmp.examenId).toBe(92);
    expect(el.textContent).toContain('IA-F1 — Cohorte de référence');
    expect(el.textContent).toContain('Chimie thérapeutique');
    expect(el.textContent).toContain('Station Défauts');
    expect(el.textContent).toContain('Maryem Khelifi');
    expect(el.textContent).toContain('REF-0002');
    expect(cmp.resume()).toEqual({ n: 2, moyenne: 20.25, max: 28.5, min: 12 });
    expect(el.textContent).toContain('Lecture seule');
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
});
