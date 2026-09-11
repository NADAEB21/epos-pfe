import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AdminExamensComponent } from './admin-examens.component';
import { ExamApiService } from '../../core/api/exam-api.service';
import { DirectoryApiService } from '../../core/api/directory-api.service';
import { ExamenResponse, PageResponse } from '../../core/api/models';

/**
 * #390 — supervision des examens de la faculté (SUPER_ADMIN), LECTURE SEULE.
 *
 * <p>Ce que ces specs épinglent (ADR-0018 D5) : la liste rend TOUS les examens
 * (le backend les sert déjà à un super-admin), le libellé de matière vient du
 * catalogue et se dégrade en « Matière n » sans casser l'écran, les filtres
 * matière/statut/texte fonctionnent, et — le point doctrinal — AUCUNE ligne ne
 * mène à `/examens/:id` (le workspace éditable) : toutes vont vers
 * `/admin/examens/:id`.
 */
describe('AdminExamensComponent — supervision lecture seule (#390)', () => {
  const examApi = { listExamens: jasmine.createSpy('listExamens') };
  const directory = { listMatieres: jasmine.createSpy('listMatieres') };

  const exam = (over: Partial<ExamenResponse>): ExamenResponse => ({
    id: 1, nom: 'Exam', matiereId: 1, dateExamen: '2026-06-20', heureDebut: null, dureeStationMin: null,
    nbEtudiantsParStation: null, statut: 'TERMINE', description: null, hasPdfSujet: false, pdfSujetNom: null,
    createdAt: null, updatedAt: null, ...over,
  });
  const page = (content: ExamenResponse[]): PageResponse<ExamenResponse> => ({
    content, page: 0, size: 100, totalElements: content.length, totalPages: 1, last: true,
  });

  function build(exams: ExamenResponse[], catalogueOk = true) {
    examApi.listExamens.and.returnValue(of(page(exams)));
    directory.listMatieres.and.returnValue(
      catalogueOk
        ? of([{ id: 1, code: 'CT', libelle: 'Chimie thérapeutique', active: true },
              { id: 12, code: 'PH', libelle: 'Pharmacologie', active: true }])
        : throwError(() => ({ status: 503 })),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AdminExamensComponent],
      providers: [
        provideRouter([]),
        { provide: ExamApiService, useValue: examApi },
        { provide: DirectoryApiService, useValue: directory },
      ],
    });
    const fixture = TestBed.createComponent(AdminExamensComponent);
    fixture.detectChanges();
    return { cmp: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  const EXAMS = [
    exam({ id: 77, nom: 'TP Chimie', matiereId: 1, statut: 'TERMINE' }),
    exam({ id: 91, nom: 'TP Pharmaco', matiereId: 12, statut: 'EN_COURS' }),
    exam({ id: 80, nom: 'IA-F1 cohorte', matiereId: 1, statut: 'TERMINE' }),
  ];

  it('rend TOUS les examens, toutes matières, libellés résolus par le catalogue', () => {
    const { cmp, el } = build(EXAMS);

    expect(cmp.allExams().length).toBe(3);
    expect(el.textContent).toContain('Chimie thérapeutique');
    expect(el.textContent).toContain('Pharmacologie');
    expect(el.textContent).toContain('Supervision — lecture seule');
  });

  it("aucune ligne ne mène au workspace : tous les liens vont vers /admin/examens/:id", () => {
    const { el } = build(EXAMS);
    const hrefs = Array.from(el.querySelectorAll('li a')).map((a) => a.getAttribute('href') ?? '');

    expect(hrefs.length).toBe(3);
    for (const h of hrefs) expect(h.startsWith('/admin/examens/')).toBeTrue();
    expect(el.textContent).not.toContain('Nouvel examen');
  });

  it('catalogue en panne : repli « Matière n », écran intact et panne dite', () => {
    const { cmp, el } = build(EXAMS, false);

    expect(cmp.catalogueEnPanne()).toBeTrue();
    expect(cmp.matiereLabel(12)).toBe('Matière 12');
    expect(el.textContent).toContain('Catalogue des matières indisponible');
    expect(cmp.allExams().length).toBe(3);
  });

  it('filtre par matière, par statut et par texte', () => {
    const { cmp } = build(EXAMS);

    cmp.onMatiereChange('1');
    expect(cmp.filteredExams().map((e) => e.id)).toEqual([77, 80]);

    cmp.onMatiereChange('');
    cmp.filter.set('EN_COURS');
    expect(cmp.visibleGroups().flatMap((g) => g.exams.map((e) => e.id))).toEqual([91]);

    cmp.filter.set('TOUS');
    cmp.search.set('pharmaco');
    expect(cmp.filteredExams().map((e) => e.id)).toEqual([91]);
  });

  it('liste vide : état honnête, pas un squelette', () => {
    const { el } = build([]);
    expect(el.textContent).toContain('Aucun examen dans la faculté');
  });

  it('exam-service en panne : erreur dite, bouton Réessayer', () => {
    examApi.listExamens.and.returnValue(throwError(() => ({ status: 503 })));
    directory.listMatieres.and.returnValue(of([]));
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [AdminExamensComponent],
      providers: [provideRouter([]), { provide: ExamApiService, useValue: examApi },
                  { provide: DirectoryApiService, useValue: directory }],
    });
    const fixture = TestBed.createComponent(AdminExamensComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.error()).toBeTrue();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Réessayer');
  });
});
