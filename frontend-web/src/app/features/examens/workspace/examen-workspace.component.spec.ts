import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ExamenWorkspaceComponent } from './examen-workspace.component';
import { ExamenWorkspaceStore } from './examen-workspace.store';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { DirectoryApiService } from '../../../core/api/directory-api.service';
import { ExamenResponse } from '../../../core/api/models';

/**
 * #378 — l'en-tête du workspace résout le LIBELLÉ de la matière depuis le
 * catalogue au lieu de rendre le repli brut « Matiere 1 ». Specs DOM (leçon
 * #359 : des specs logique-seulement laissent livrer l'invisible) : ce que
 * l'en-tête REND, catalogue présent comme en panne.
 */
describe('ExamenWorkspaceComponent — #378 libellé matière', () => {
  let examApi: jasmine.SpyObj<ExamApiService>;
  let scoring: jasmine.SpyObj<ScoringApiService>;
  let directory: jasmine.SpyObj<DirectoryApiService>;

  const exam = {
    id: 77,
    nom: 'Examen pratique',
    statut: 'TERMINE',
    dateExamen: '2026-08-20',
    matiereId: 1,
    stations: [],
    hasPdfSujet: false,
  } as unknown as ExamenResponse;

  beforeEach(() => {
    examApi = jasmine.createSpyObj('ExamApiService', [
      'getExamen',
      'listStations',
      'listConflitsEvaluateurs',
      'listBaremesIncomplets',
    ]);
    scoring = jasmine.createSpyObj('ScoringApiService', ['listParticipations', 'listLots']);
    directory = jasmine.createSpyObj('DirectoryApiService', ['listUsers', 'listMatieres']);

    examApi.getExamen.and.returnValue(of(exam));
    examApi.listStations.and.returnValue(of([]));
    examApi.listConflitsEvaluateurs.and.returnValue(of([]));
    examApi.listBaremesIncomplets.and.returnValue(of([]));
    scoring.listParticipations.and.returnValue(of([]));
    scoring.listLots.and.returnValue(of([]));
    directory.listUsers.and.returnValue(of([]));
    directory.listMatieres.and.returnValue(
      of([
        { id: 1, code: 'CHIM_THER', libelle: 'Chimie thérapeutique', active: true },
        { id: 4, code: 'PHARMACO', libelle: 'Pharmacologie', active: true },
      ]),
    );

    TestBed.configureTestingModule({
      imports: [ExamenWorkspaceComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ExamApiService, useValue: examApi },
        { provide: ScoringApiService, useValue: scoring },
        { provide: DirectoryApiService, useValue: directory },
        ExamenWorkspaceStore,
      ],
    });
  });

  function create(): ComponentFixture<ExamenWorkspaceComponent> {
    const fixture = TestBed.createComponent(ExamenWorkspaceComponent);
    fixture.componentRef.setInput('id', '77');
    // Two passes: the first runs the constructor effect (store.load → signals
    // settle synchronously on the of() mocks), the second renders the header.
    fixture.detectChanges();
    fixture.detectChanges();
    return fixture;
  }

  function headerLine(fixture: ComponentFixture<ExamenWorkspaceComponent>): string {
    return (
      (fixture.nativeElement as HTMLElement).querySelector('header p')?.textContent ?? ''
    );
  }

  it("rend le LIBELLÉ de la matière dans l'en-tête, pas l'id brut", () => {
    const fixture = create();

    const line = headerLine(fixture);
    expect(line).toContain('Chimie thérapeutique');
    expect(line).not.toContain('Matiere 1');
  });

  it('catalogue en panne : repli numérique « Matiere 1 », jamais un en-tête vide', () => {
    directory.listMatieres.and.returnValue(throwError(() => ({ status: 503 })));
    const fixture = create();

    const line = headerLine(fixture);
    expect(line).toContain('Matiere 1');
    expect(line).toContain('2026-08-20'); // le reste de la ligne vit toujours
  });

  it("matière absente du catalogue : repli numérique (l'en-tête ne casse pas)", () => {
    directory.listMatieres.and.returnValue(
      of([{ id: 9, code: 'AUTRE', libelle: 'Autre matière', active: true }]),
    );
    const fixture = create();

    expect(headerLine(fixture)).toContain('Matiere 1');
  });
});
