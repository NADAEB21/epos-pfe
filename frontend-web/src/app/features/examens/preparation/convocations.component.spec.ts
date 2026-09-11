import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { ConvocationsComponent } from './convocations.component';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import { Convocation, ExamenResponse } from '../../../core/api/models';
import { todayStr } from '../../../core/api/exam-status';

/**
 * #433 — les convocations ne partent pas vers une date révolue. L'écran le dit AVANT le
 * clic (bouton désactivé + encadré nominatif), refuse localement sans aller-retour, et
 * affiche mot pour mot un refus du serveur (page obsolète).
 */
describe('ConvocationsComponent — #433 date passée', () => {
  let scoring: jasmine.SpyObj<ScoringApiService>;
  let examApi: jasmine.SpyObj<ExamApiService>;

  function iso(offsetDays: number): string {
    const d = new Date();
    d.setDate(d.getDate() + offsetDays);
    return todayStr(d);
  }

  function conv(participationId: number, jour: string | null, email: string | null = 'x@etu.tn'): Convocation {
    return {
      participationId,
      etudiantId: participationId,
      nom: 'Nom' + participationId,
      prenom: 'P',
      numero_inscription: 'N' + participationId,
      email,
      ordre_import: participationId,
      lotId: 1,
      lotNumero: 1,
      jour,
      heureConvocation: '09:00',
      convocationEnvoyeeA: null,
    };
  }

  function build(dateExamen: string, convocations: Convocation[]): ConvocationsComponent {
    scoring = jasmine.createSpyObj('ScoringApiService', [
      'listConvocations',
      'listParticipations',
      'envoyerConvocations',
      'updateEtudiant',
    ]);
    examApi = jasmine.createSpyObj('ExamApiService', ['getExamen', 'listStations']);
    examApi.getExamen.and.returnValue(of({ id: 10, dateExamen, statut: 'CONFIGURE', dureeStationMin: 10 } as ExamenResponse));
    examApi.listStations.and.returnValue(of([]));
    scoring.listConvocations.and.returnValue(of(convocations));
    scoring.listParticipations.and.returnValue(of([]));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [ConvocationsComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ScoringApiService, useValue: scoring },
        { provide: ExamApiService, useValue: examApi },
        ExamenWorkspaceStore,
      ],
    });
    const fixture = TestBed.createComponent(ConvocationsComponent);
    const component = fixture.componentInstance;
    (component as unknown as { id: () => string }).id = () => '10';
    fixture.detectChanges();
    return component;
  }

  it('date d’hier, un seul jour → datePassee, envoi refusé SANS appel serveur, message nominatif', () => {
    const c = build(iso(-1), [conv(1, null)]);

    expect(c.datePassee()).toBeTrue();
    c.envoyer();
    expect(scoring.envoyerConvocations).not.toHaveBeenCalled();
    expect(c.envoiError()).toContain('est passée');
    expect(c.envoiError()).toContain(c.dernierJourLabel());
  });

  it('multi-jour : examen daté d’hier mais un lot aujourd’hui → le DERNIER jour compte, envoi permis', () => {
    scoring = jasmine.createSpyObj('ScoringApiService', ['listConvocations', 'listParticipations', 'envoyerConvocations', 'updateEtudiant']);
    const c = build(iso(-1), [conv(1, iso(-1)), conv(2, iso(0))]);

    expect(c.dernierJour()).toBe(iso(0));
    expect(c.datePassee()).toBeFalse();
  });

  it('date de demain → rien ne bloque', () => {
    const c = build(iso(1), [conv(1, null)]);
    expect(c.datePassee()).toBeFalse();
  });

  it('refus du serveur (page obsolète) → le message serveur est affiché mot pour mot', () => {
    const c = build(iso(1), [conv(1, null)]);
    scoring.envoyerConvocations.and.returnValue(
      throwError(() => ({ error: { message: "La date de l'examen (09/09/2026) est passée : modifiez-la dans Planning." } })),
    );

    c.envoyer();

    expect(c.envoiError()).toContain('09/09/2026');
  });
});
