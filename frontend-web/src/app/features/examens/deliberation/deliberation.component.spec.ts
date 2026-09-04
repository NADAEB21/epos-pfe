import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { DeliberationComponent } from './deliberation.component';
import { AiApiService } from '../../../core/api/ai-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import { ExamenResponse, PropositionsExamen } from '../../../core/api/models';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

/**
 * #363 (N9) — le flux D10 complet, épinglé : l'effet projeté est RENDU avant
 * tout clic ; accepter = POST scoring (version complète) PUIS décision avec la
 * version rendue ; refuser = décision seule, JAMAIS de POST scoring ; une
 * journalisation en échec ne cache pas le barème écrit ; le module IA absent
 * n'emporte ni l'historique ni la composition ; un code inconnu dégrade
 * visiblement ; la prévisualisation manuelle passe par /projection.
 */
describe('DeliberationComponent — proposition → effet projeté → acte motivé (#363)', () => {
  let ai: jasmine.SpyObj<AiApiService>;
  let scoring: jasmine.SpyObj<ScoringApiService>;
  let examApi: jasmine.SpyObj<ExamApiService>;

  const EXAM: ExamenResponse = {
    id: 92, nom: 'IA-F1 — twin', matiereId: 1, dateExamen: '2026-06-20', heureDebut: null, dureeStationMin: null,
    nbEtudiantsParStation: null, statut: 'TERMINE', description: null, hasPdfSujet: false, pdfSujetNom: null,
    createdAt: null, updatedAt: null,
  };

  function payload(): PropositionsExamen {
    return {
      examen_id: 92, entrees_hash: 'h'.repeat(64), moteur_version: 'n8-test', bareme_courant: null,
      couverture_snapshot_complete: true,
      seuils: { p_impossible: 0.1, r_nul: 0.1, alpha_reference: 0.5, taux_echec_station: 0.5, p_value_concentration: 0.05 },
      propositions: [{
        proposition_id: 'a1b2', rang_defendabilite: 1, lecture_code: 'CRITERE_IMPOSSIBLE',
        operation: { type: 'EXCLURE_CRITERE', cibleItemId: 280, cibleStationId: null, nouvelleEchelle: null },
        operations_a_soumettre: [{ type: 'EXCLURE_CRITERE', cibleItemId: 280, cibleStationId: null, nouvelleEchelle: null }],
        cible: { item_id: 280, libelle: 'Critère impossible', type: 'BINAIRE', grille_id: 111, station_id: 124, max: 5 },
        declencheur: [{ code: 'DIFFICULTE', valeur: 0.0556, ic: [0, 0.14], n: 36, seuil: 0.1, regle: 'p <= seuil' }],
        effet_projete: {
          origine: { n_etudiants: 36, denominateur: 60, mediane: 28.5, moyenne: 29.83, taux_reussite: 0.4722 },
          avant: { n_etudiants: 36, denominateur: 60, mediane: 28.5, moyenne: 29.83, taux_reussite: 0.4722 },
          apres: { n_etudiants: 36, denominateur: 55, mediane: 28.5, moyenne: 29.56, taux_reussite: 0.5556 },
        },
        deja_appliquee: false, decision: null,
      }],
      lectures_sans_proposition: [
        { code: 'GRILLE_INCOHERENTE', grille_id: 111, station_id: 124, details: {}, raison: 'le total de référence de cette grille est lui-même incohérent' },
        { code: 'REPONDERATION_JAMAIS_AUTOMATIQUE', details: {}, raison: 'la repondération est un jugement' },
      ],
    };
  }

  let scoringHistoriqueVide = false;

  function build(opts: { statut?: ExamenResponse['statut']; aiDown?: boolean; payload?: PropositionsExamen } = {}) {
    ai = jasmine.createSpyObj('AiApiService', ['getPropositions', 'deciderProposition', 'projeter']);
    scoring = jasmine.createSpyObj('ScoringApiService', ['listBaremesDeliberation', 'creerBaremeDeliberation', 'getExamenGrillesSnapshot']);
    examApi = jasmine.createSpyObj('ExamApiService', ['listStations']);
    ai.getPropositions.and.returnValue(opts.aiDown ? throwError(() => ({ status: 503 })) : of(opts.payload ?? payload()));
    ai.deciderProposition.and.returnValue(of({ decision: 'ACCEPTER', motif: 'm', decide_par: 2, decide_a: null, bareme_version_resultat: 1, proposition_id: 'a1b2' }));
    ai.projeter.and.returnValue(of({
      examen_id: 92, bareme_courant: null,
      operations: [{ type: 'REPONDERER', cibleItemId: null, cibleStationId: 124, nouvelleEchelle: 10 }],
      couverture_snapshot_complete: true, max_delibere_par_station: { '124': 10 }, max_original_par_station: { '124': 20 },
      effet_projete: {
        origine: { n_etudiants: 36, denominateur: 60, mediane: 28.5, moyenne: 29.83, taux_reussite: 0.4722 },
        avant: { n_etudiants: 36, denominateur: 60, mediane: 28.5, moyenne: 29.83, taux_reussite: 0.4722 },
        apres: { n_etudiants: 36, denominateur: 50, mediane: 24.5, moyenne: 25.82, taux_reussite: 0.4722 },
      },
    }));
    scoring.listBaremesDeliberation.and.returnValue(of(scoringHistoriqueVide ? [] : [
      { id: 9, examenId: 92, version: 1, motif: 'Personne n a pu marquer', creePar: 2, createdAt: '2026-09-01T22:24:00',
        operations: [{ type: 'EXCLURE_CRITERE', cibleItemId: 280, cibleStationId: null, nouvelleEchelle: null }] },
    ]));
    scoring.creerBaremeDeliberation.and.returnValue(of({ id: 10, examenId: 92, version: 2, motif: 'm', creePar: 2, createdAt: null, operations: [] }));
    scoring.getExamenGrillesSnapshot.and.returnValue(of([
      { stationId: 124, grilleId: 111, nom: 'Station Défauts', noteMax: 20, items: [{ id: 280, libelle: 'Critère impossible' }, { id: 279, libelle: 'Geste conforme' }] },
    ]));
    examApi.listStations.and.returnValue(of([{ id: 124, nom: 'Station Défauts' }]));

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [DeliberationComponent],
      providers: [
        provideHttpClient(), provideHttpClientTesting(),
        ExamenWorkspaceStore,
        { provide: AiApiService, useValue: ai },
        { provide: ScoringApiService, useValue: scoring },
        { provide: ExamApiService, useValue: examApi },
      ],
    });
    TestBed.inject(ExamenWorkspaceStore).exam.set({ ...EXAM, statut: opts.statut ?? 'TERMINE' });
    const fixture = TestBed.createComponent(DeliberationComponent);
    (fixture.componentInstance as unknown as { id: () => string }).id = () => '92';
    fixture.detectChanges();
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, el: fixture.nativeElement as HTMLElement };
  }

  it("#363 : l'effet projeté est RENDU avant tout clic — dénominateur, médiane, réussite, avant → après", () => {
    const { el } = build();
    const t = el.textContent ?? '';
    expect(t).toContain('Critère impossible');
    expect(t).toContain('Ce que cela changerait pour les 36 étudiants');
    expect(t).toContain('47 %');
    expect(t).toContain('56 %');
    expect(t).toContain('28,5 / 55');
    expect(t).toContain('Accepter…');
    expect(t).toContain('Refuser…');
    expect(t).toContain('Grille incohérente');
    expect(t).toContain('le total de référence de cette grille est lui-même incohérent');
  });

  it('#363 : accepter = POST scoring de la version COMPLÈTE avec motif, PUIS décision avec la version rendue', () => {
    const { c } = build();
    const p = c.propositions()!.propositions[0];
    c.ouvrirDecision(p, 'ACCEPTER');
    c.confirmerDecision(p);
    expect(scoring.creerBaremeDeliberation).not.toHaveBeenCalled();
    expect(c.erreur()).toContain('obligatoire');

    c.motif.set('  Personne n a pu marquer  ');
    c.confirmerDecision(p);

    expect(scoring.creerBaremeDeliberation).toHaveBeenCalledWith(92, {
      motif: 'Personne n a pu marquer', operations: p.operations_a_soumettre,
    });
    expect(ai.deciderProposition).toHaveBeenCalledWith(92, 'a1b2', {
      decision: 'ACCEPTER', motif: 'Personne n a pu marquer', bareme_version_resultat: 2,
    });
    expect(c.succes()).toContain('v2');
    expect(c.decisionOuverte()).toBeNull();
  });

  it('#363 : refuser = décision journalisée seule — JAMAIS de POST scoring', () => {
    const { c } = build();
    const p = c.propositions()!.propositions[0];
    c.ouvrirDecision(p, 'REFUSER');
    c.motif.set('On garde le critère');
    c.confirmerDecision(p);

    expect(scoring.creerBaremeDeliberation).not.toHaveBeenCalled();
    expect(ai.deciderProposition).toHaveBeenCalledWith(92, 'a1b2', {
      decision: 'REFUSER', motif: 'On garde le critère', bareme_version_resultat: null,
    });
    expect(c.succes()).toContain('Refus enregistré');
  });

  it("#363 : journalisation en échec après un barème écrit → l'écran le DIT, ne cache rien, ne retente pas le POST", () => {
    const { c } = build();
    ai.deciderProposition.and.returnValue(throwError(() => ({ status: 503, error: { message: 'Plan de données du module IA indisponible' } })));
    const p = c.propositions()!.propositions[0];
    c.ouvrirDecision(p, 'ACCEPTER');
    c.motif.set('m');
    c.confirmerDecision(p);

    expect(scoring.creerBaremeDeliberation).toHaveBeenCalledTimes(1);
    expect(c.avertissement()).toContain('v2 est bien enregistré');
    expect(c.avertissement()).toContain('Plan de données du module IA indisponible');
    expect(c.erreur()).toBeNull();
  });

  it('#363 : le refus du serveur (409 non clos, 403) s’affiche MOT POUR MOT ; 403 scoring → phrase nominative', () => {
    const { c } = build();
    scoring.creerBaremeDeliberation.and.returnValue(throwError(() => ({ status: 409, error: { message: "L'examen 92 est EN_COURS — le barème n'est possible qu'une fois clos" } })));
    const p = c.propositions()!.propositions[0];
    c.ouvrirDecision(p, 'ACCEPTER');
    c.motif.set('m');
    c.confirmerDecision(p);
    expect(c.erreur()).toContain('EN_COURS');

    scoring.creerBaremeDeliberation.and.returnValue(throwError(() => ({ status: 403, error: { message: 'Access denied' } })));
    c.ouvrirDecision(p, 'ACCEPTER');
    c.motif.set('m');
    c.confirmerDecision(p);
    expect(c.erreur()).toContain('hors de votre matière');
  });

  it("#363 : module IA absent → note dite, historique et composition restent servis", () => {
    const { c, el } = build({ aiDown: true });
    expect(c.aiEtat()).toBe('absents');
    const t = el.textContent ?? '';
    expect(t).toContain('Propositions non disponibles');
    expect(t).toContain('v1');
    expect(t).toContain('Personne n a pu marquer');
    expect(t).toContain('Retirer le critère « Critère impossible »');
    expect(t).toContain('Modifier…');
  });

  it("#363 : examen non clos → aucun acte possible, l'historique reste lisible", () => {
    const { c, el } = build({ statut: 'EN_COURS' });
    expect(c.canDeliberer()).toBeFalse();
    const t = el.textContent ?? '';
    expect(t).toContain("n'est possible qu'une fois l'examen clos");
    expect(t).not.toContain('Accepter…');
    expect(t).not.toContain('Modifier…');
  });

  it('#363 : un code de proposition inconnu dégrade VISIBLEMENT (pas d’écran cassé, pas d’invention)', () => {
    const pl = payload();
    pl.propositions[0].lecture_code = 'NOUVEAU_CODE_2027';
    const { el } = build({ payload: pl });
    const t = el.textContent ?? '';
    expect(t).toContain('NOUVEAU_CODE_2027');
    expect(t).toContain('lecture indisponible');
    expect(t).toContain('Accepter…'); // l'acte reste possible : l'opération, elle, est connue
  });

  it('#363 : composition manuelle — prévisualisation par /projection AVANT l’acte, motif obligatoire', () => {
    const { c } = build();
    c.ouvrirComposition();
    c.onCompoType('REPONDERER');
    c.onCompoStation('124');
    c.onCompoEchelle('10');
    c.ajouterOperation();
    // #399 : la composition part du barème COURANT (v1 scoring : 1 opération) — les
    // versions sont complètes, une composition ne remplace jamais en silence.
    expect(c.composition()).toEqual([
      { type: 'EXCLURE_CRITERE', cibleItemId: 280, cibleStationId: null, nouvelleEchelle: null },
      { type: 'REPONDERER', cibleItemId: null, cibleStationId: 124, nouvelleEchelle: 10 },
    ]);

    c.compoMotif.set('m');
    c.appliquerComposition();
    expect(scoring.creerBaremeDeliberation).not.toHaveBeenCalled();
    expect(c.compoActErreur()).toContain('Prévisualisez');

    c.previsualiser();
    expect(ai.projeter).toHaveBeenCalledWith(92, c.composition());
    expect(c.projection()?.effet_projete?.apres.denominateur).toBe(50);

    c.appliquerComposition();
    expect(scoring.creerBaremeDeliberation).toHaveBeenCalledWith(92, { motif: 'm', operations: c.composition() });
    expect(c.succes()).toContain('v2');
  });

  it('#363 : « déjà appliquée » et décision existante → aucun bouton d’acte, la décision est affichée', () => {
    const pl = payload();
    pl.bareme_courant = { version: 1, operations: pl.propositions[0].operations_a_soumettre };
    pl.propositions[0].deja_appliquee = true;
    pl.propositions[0].decision = { decision: 'ACCEPTER', motif: 'ok', decide_par: 2, decide_a: '2026-09-01T22:25:02', bareme_version_resultat: 1, proposition_id: 'old' };
    const { el } = build({ payload: pl });
    const t = el.textContent ?? '';
    expect(t).toContain('déjà appliquée');
    expect(t).toContain('Acceptée — v1');
    expect(t).not.toContain('Accepter…');
    expect(t).toContain('Barème courant : v1');
  });
  // ---- #399 (constat Feten) : le barème courant vient de SCORING, jamais du module IA ------

  it('#399 : module IA absent → l’en-tête montre quand même « Barème courant : v1 » (source scoring)', () => {
    const { c, el } = build({ aiDown: true });
    expect(c.baremeSource()).toBe('scoring');
    expect(c.baremeCourant()?.version).toBe(1);
    expect(el.textContent).toContain('Barème courant : v1');
    expect(el.textContent).not.toContain('scoring injoignable');
  });

  it('#399 : module IA absent → la composition est semée avec les opérations COURANTES (jamais vide)', () => {
    const { c } = build({ aiDown: true });
    c.ouvrirComposition();
    expect(c.compositionOuverte()).toBeTrue();
    expect(c.composition().length).toBe(1);
    expect(c.composition()[0].cibleItemId).toBe(280);
  });

  it('#399 : scoring ET module IA muets → l’éditeur REFUSE de s’ouvrir, et le dit', () => {
    const { c, fixture, el } = build({ aiDown: true });
    scoring.listBaremesDeliberation.and.returnValue(throwError(() => ({ status: 503 })));
    c.reload();
    fixture.detectChanges();
    expect(c.baremeSource()).toBeNull();
    c.ouvrirComposition();
    fixture.detectChanges();
    expect(c.compositionOuverte()).toBeFalse();
    expect(el.textContent).toContain('remplacerait les modifications');
  });

  it('#399 : historique scoring vide → « Aucun barème », même quand le module IA est absent', () => {
    scoringHistoriqueVide = true;
    const { c, el } = build({ aiDown: true });
    scoringHistoriqueVide = false;
    expect(c.baremeCourant()).toBeNull();
    expect(el.textContent).toContain('Aucun barème de délibération');
  });
});
