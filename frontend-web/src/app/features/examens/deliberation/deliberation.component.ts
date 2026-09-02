import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { catchError, forkJoin, of } from 'rxjs';
import { AiApiService } from '../../../core/api/ai-api.service';
import { ExamApiService } from '../../../core/api/exam-api.service';
import { ScoringApiService } from '../../../core/api/scoring-api.service';
import {
  BaremeDeliberation,
  EffetProjeteAi,
  GrilleItem,
  OperationBareme,
  ProjectionAi,
  PropositionAi,
  PropositionsExamen,
  StationGrilleSnapshot,
  StationSummary,
  TypeOperationBareme,
} from '../../../core/api/models';
import {
  fmtNum,
  fmtPct,
  libelleDeclencheur,
  libelleLecture,
  libelleOperation,
  phraseProposition,
  sur20,
} from '../../../shared/ia/lecture-deliberation';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';

/** Une lecture de proposition, dégradée VISIBLEMENT sur un code inconnu (leçon PR #385). */
interface LectureProposition {
  libelle: string;
  phrase: string;
  degrade: boolean;
}

type EtatAi = 'chargement' | 'prets' | 'absents' | 'refus';
type EtatHistorique = 'chargement' | 'prets' | 'indisponible';

/**
 * #363 (N9) — l'écran de DÉLIBÉRATION : le flux ADR-0021 D10 complet.
 *
 *   proposition (N8) → effet PROJETÉ affiché AVANT le clic → acte MOTIVÉ.
 *
 * Trois sources indépendantes, qui dégradent séparément et le DISENT :
 *  - ai-service `GET /propositions` (fail-soft : « absents », ou le refus
 *    nominatif du serveur — 409 examen non clos, 403 hors matière) ;
 *  - scoring `GET /bareme-deliberation` (l'historique des versions, motifs
 *    visibles — ADR-0030 D4) ;
 *  - exam-service stations + snapshot des grilles, pour NOMMER les cibles.
 *
 * L'ACTE (ADR-0030 D1) : accepter = POST scoring de `operations_a_soumettre`
 * (la version COMPLÈTE) avec le motif, PUIS journaliser la décision côté
 * ai-service avec la version rendue. Les deux appels ne sont pas atomiques
 * et l'écran ne fait pas semblant : si la journalisation échoue, le barème
 * existe (c'est la DONNÉE) et l'écran le dit — un re-GET montre
 * `deja_appliquee` sans décision, jamais l'un déduit de l'autre. Refuser =
 * décision seule, journalisée aussi. Le module IA n'écrit JAMAIS vers scoring.
 *
 * La composition MANUELLE (repondération comprise — jamais proposée
 * automatiquement, D8) passe par `POST /projection` : la prévisualisation
 * utilise la MÊME arithmétique que scoring, l'effet n'est jamais découvert
 * après coup.
 */
@Component({
  selector: 'app-deliberation',
  standalone: true,
  imports: [DecimalPipe, DatePipe],
  templateUrl: './deliberation.component.html',
})
export class DeliberationComponent {
  private readonly ai = inject(AiApiService);
  private readonly scoring = inject(ScoringApiService);
  private readonly examApi = inject(ExamApiService);
  private readonly store = inject(ExamenWorkspaceStore);

  readonly id = input.required<string>();
  readonly examenIdNum = computed(() => Number(this.id()));
  readonly exam = this.store.exam;

  /** L'acte n'est légal que sur un examen CLOS (TERMINE ou ARCHIVE — ADR-0030 D5, ai-service STATUTS_CLOS). */
  readonly canDeliberer = computed(() => {
    const s = this.exam()?.statut;
    return s === 'TERMINE' || s === 'ARCHIVE';
  });

  // ---- propositions (ai-service) ------------------------------------------------
  readonly propositions = signal<PropositionsExamen | null>(null);
  readonly aiEtat = signal<EtatAi>('chargement');
  /** Le refus du serveur, VERBATIM (409 non clos, 403 hors matière…). */
  readonly aiMessage = signal<string | null>(null);

  // ---- historique (scoring) -------------------------------------------------------
  readonly historique = signal<BaremeDeliberation[]>([]);
  readonly histEtat = signal<EtatHistorique>('chargement');

  // ---- noms des cibles ------------------------------------------------------------
  readonly stations = signal<StationSummary[]>([]);
  readonly grilles = signal<StationGrilleSnapshot[]>([]);

  // ---- décision sur une proposition -----------------------------------------------
  readonly decisionOuverte = signal<{ propositionId: string; decision: 'ACCEPTER' | 'REFUSER' } | null>(null);
  readonly motif = signal('');
  readonly busy = signal(false);
  readonly erreur = signal<string | null>(null);
  /** Barème écrit mais décision NON journalisée — dit, jamais caché. */
  readonly avertissement = signal<string | null>(null);
  readonly succes = signal<string | null>(null);

  // ---- composition manuelle (prévisualisation D10) --------------------------------
  readonly compositionOuverte = signal(false);
  readonly composition = signal<OperationBareme[]>([]);
  readonly compoType = signal<TypeOperationBareme>('EXCLURE_CRITERE');
  readonly compoStationId = signal<number | null>(null);
  readonly compoItemId = signal<number | null>(null);
  readonly compoEchelle = signal<number | null>(null);
  readonly compoErreur = signal<string | null>(null);
  readonly projection = signal<ProjectionAi | null>(null);
  readonly projectionBusy = signal(false);
  readonly compoMotif = signal('');
  readonly compoBusy = signal(false);
  readonly compoActErreur = signal<string | null>(null);

  readonly baremeCourant = computed(() => this.propositions()?.bareme_courant ?? null);

  readonly nomsCibles = computed(() => {
    const stations = new Map<number, string>();
    for (const s of this.stations()) stations.set(s.id, s.nom ?? `Station ${s.id}`);
    const criteres = new Map<number, string>();
    for (const g of this.grilles()) {
      if (!stations.has(g.stationId)) stations.set(g.stationId, g.nom);
      for (const it of this.aplatir(g.items ?? [])) criteres.set(it.id, it.libelle);
    }
    return {
      station: (id: number) => stations.get(id) ?? `Station ${id}`,
      critere: (id: number) => criteres.get(id) ?? `Critère ${id}`,
    };
  });

  /** Les critères FEUILLES d'une station (pour la composition), depuis le snapshot. */
  readonly criteresParStation = computed(() => {
    const out = new Map<number, GrilleItem[]>();
    for (const g of this.grilles()) out.set(g.stationId, this.aplatir(g.items ?? []));
    return out;
  });

  readonly criteresDeLaStationChoisie = computed(() => {
    const sid = this.compoStationId();
    return sid == null ? [] : (this.criteresParStation().get(sid) ?? []);
  });

  constructor() {
    effect(
      () => {
        const examId = Number(this.id());
        if (!Number.isFinite(examId)) return;
        this.load(examId);
      },
      { allowSignalWrites: true },
    );
  }

  load(examId: number): void {
    this.loadPropositions(examId);
    this.loadHistorique(examId);
    forkJoin({
      stations: this.examApi.listStations(examId).pipe(catchError(() => of([] as StationSummary[]))),
      grilles: this.scoring
        .getExamenGrillesSnapshot(examId)
        .pipe(catchError(() => of([] as StationGrilleSnapshot[]))),
    }).subscribe(({ stations, grilles }) => {
      this.stations.set(stations);
      this.grilles.set(grilles);
    });
  }

  reload(): void {
    this.load(this.examenIdNum());
  }

  /**
   * Chargé SÉPARÉMENT (même raison que les indices de Résultats) : une panne
   * du module IA ne doit jamais emporter l'historique ni la composition.
   */
  private loadPropositions(examId: number): void {
    this.aiEtat.set('chargement');
    this.aiMessage.set(null);
    this.ai.getPropositions(examId).subscribe({
      next: (data) => {
        this.propositions.set(data);
        this.aiEtat.set('prets');
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        const message = err?.error?.message;
        if ((err?.status === 409 || err?.status === 403) && message) {
          // Refus NOMINATIF du serveur : on le montre tel quel.
          this.aiEtat.set('refus');
          this.aiMessage.set(message);
        } else {
          this.aiEtat.set('absents');
        }
      },
    });
  }

  private loadHistorique(examId: number): void {
    this.histEtat.set('chargement');
    this.scoring.listBaremesDeliberation(examId).subscribe({
      next: (h) => {
        this.historique.set(h);
        this.histEtat.set('prets');
      },
      error: () => this.histEtat.set('indisponible'),
    });
  }

  // ---- lectures ----------------------------------------------------------------------

  lecture(p: PropositionAi): LectureProposition {
    try {
      return { libelle: libelleLecture(p.lecture_code), phrase: phraseProposition(p.lecture_code), degrade: false };
    } catch {
      return {
        libelle: p.lecture_code,
        phrase: 'lecture indisponible — code de proposition non reconnu par cette version du site',
        degrade: true,
      };
    }
  }

  libelleSansProposition(code: string): { libelle: string; degrade: boolean } {
    try {
      return { libelle: libelleLecture(code), degrade: false };
    } catch {
      return { libelle: code, degrade: true };
    }
  }

  declencheur(d: PropositionAi['declencheur'][number]): string {
    return libelleDeclencheur(d);
  }

  operation(op: OperationBareme): string {
    try {
      return libelleOperation(op, this.nomsCibles());
    } catch {
      return `${op.type} (${op.cibleItemId ?? op.cibleStationId ?? '?'})`;
    }
  }

  cibleLabel(p: PropositionAi): string {
    const c = p.cible;
    if (c.item_id != null) {
      const st = c.station_id != null ? ` — ${this.nomsCibles().station(c.station_id)}` : '';
      return `${c.libelle ?? this.nomsCibles().critere(c.item_id)}${st}`;
    }
    return c.station_id != null ? this.nomsCibles().station(c.station_id) : '—';
  }

  rangLabel(rang: number): string {
    return rang === 1 ? 'Rang 1 — observation' : rang === 2 ? 'Rang 2 — station' : `Rang ${rang}`;
  }

  fmt(x: number | null | undefined, d = 2): string {
    return fmtNum(x, d);
  }

  pct(x: number | null | undefined): string {
    return fmtPct(x);
  }

  sur20(v: number | null | undefined, d: number | null | undefined): string {
    return sur20(v, d);
  }

  /** L'effet AVANT → APRÈS, ligne par ligne (D10 : lisible avant le clic). */
  lignesEffet(e: EffetProjeteAi | null): { label: string; avant: string; apres: string; change: boolean }[] {
    if (!e) return [];
    return [
      { label: 'Dénominateur', avant: fmtNum(e.avant.denominateur, 0), apres: fmtNum(e.apres.denominateur, 0), change: e.avant.denominateur !== e.apres.denominateur },
      { label: 'Médiane', avant: sur20(e.avant.mediane, e.avant.denominateur), apres: sur20(e.apres.mediane, e.apres.denominateur), change: e.avant.mediane !== e.apres.mediane || e.avant.denominateur !== e.apres.denominateur },
      { label: 'Moyenne', avant: sur20(e.avant.moyenne, e.avant.denominateur), apres: sur20(e.apres.moyenne, e.apres.denominateur), change: e.avant.moyenne !== e.apres.moyenne || e.avant.denominateur !== e.apres.denominateur },
      { label: 'Taux de réussite (≥ moitié)', avant: fmtPct(e.avant.taux_reussite), apres: fmtPct(e.apres.taux_reussite), change: e.avant.taux_reussite !== e.apres.taux_reussite },
    ];
  }

  private aplatir(items: GrilleItem[]): GrilleItem[] {
    const out: GrilleItem[] = [];
    const walk = (list: GrilleItem[]) => {
      for (const it of list) {
        const sous = (it as GrilleItem & { sousCriteres?: GrilleItem[] }).sousCriteres;
        if (sous && sous.length > 0) walk(sous);
        else out.push(it);
      }
    };
    walk(items);
    return out;
  }

  // ---- décision -------------------------------------------------------------------------

  ouvrirDecision(p: PropositionAi, decision: 'ACCEPTER' | 'REFUSER'): void {
    this.decisionOuverte.set({ propositionId: p.proposition_id, decision });
    this.motif.set('');
    this.erreur.set(null);
    this.succes.set(null);
  }

  annulerDecision(): void {
    this.decisionOuverte.set(null);
    this.motif.set('');
    this.erreur.set(null);
  }

  confirmerDecision(p: PropositionAi): void {
    const ouverte = this.decisionOuverte();
    if (!ouverte || ouverte.propositionId !== p.proposition_id || this.busy()) return;
    const motif = this.motif().trim();
    if (!motif) {
      this.erreur.set('Le motif est obligatoire — la délibération se raconte (ADR-0030 D1).');
      return;
    }
    this.busy.set(true);
    this.erreur.set(null);
    this.avertissement.set(null);
    const examId = this.examenIdNum();

    if (ouverte.decision === 'REFUSER') {
      this.ai.deciderProposition(examId, p.proposition_id, { decision: 'REFUSER', motif, bareme_version_resultat: null })
        .subscribe({
          next: () => {
            this.busy.set(false);
            this.decisionOuverte.set(null);
            this.succes.set('Refus journalisé — le barème reste inchangé.');
            this.reload();
          },
          error: (err: { status?: number; error?: { message?: string } }) => {
            this.busy.set(false);
            this.erreur.set(this.messageErreur(err, 'Le refus n’a pas pu être journalisé.'));
          },
        });
      return;
    }

    // ACCEPTER : 1) le barème dans scoring (l'acte), 2) la décision dans ai_db (la trace).
    this.scoring.creerBaremeDeliberation(examId, { motif, operations: p.operations_a_soumettre }).subscribe({
      next: (version) => {
        this.ai
          .deciderProposition(examId, p.proposition_id, {
            decision: 'ACCEPTER',
            motif,
            bareme_version_resultat: version.version,
          })
          .subscribe({
            next: () => {
              this.busy.set(false);
              this.decisionOuverte.set(null);
              this.succes.set(`Barème de délibération v${version.version} enregistré et décision journalisée.`);
              this.reload();
            },
            error: (err: { status?: number; error?: { message?: string } }) => {
              this.busy.set(false);
              this.decisionOuverte.set(null);
              this.avertissement.set(
                `Le barème v${version.version} est bien enregistré dans scoring, mais la décision n'a pas pu être journalisée côté module IA : `
                  + this.messageErreur(err, 'module indisponible') + ' — rechargez : la proposition apparaîtra comme déjà appliquée.',
              );
              this.reload();
            },
          });
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.busy.set(false);
        this.erreur.set(this.messageErreur(err, 'Le barème n’a pas pu être enregistré.'));
      },
    });
  }

  private messageErreur(err: { status?: number; error?: { message?: string } }, defaut: string): string {
    if (err?.status === 403) return "Vous n'avez pas les droits pour délibérer sur cet examen (hors de votre matière).";
    return err?.error?.message ?? defaut;
  }

  // ---- composition manuelle ------------------------------------------------------------

  ouvrirComposition(): void {
    this.composition.set([...(this.baremeCourant()?.operations ?? [])]);
    this.projection.set(null);
    this.compoErreur.set(null);
    this.compoActErreur.set(null);
    this.compoMotif.set('');
    this.compositionOuverte.set(true);
  }

  fermerComposition(): void {
    this.compositionOuverte.set(false);
  }

  onCompoType(v: string): void {
    this.compoType.set(v as TypeOperationBareme);
    this.compoItemId.set(null);
    this.compoEchelle.set(null);
  }

  onCompoStation(v: string): void {
    this.compoStationId.set(v ? Number(v) : null);
    this.compoItemId.set(null);
  }

  onCompoItem(v: string): void {
    this.compoItemId.set(v ? Number(v) : null);
  }

  onCompoEchelle(v: string): void {
    const n = Number(v);
    this.compoEchelle.set(v === '' || Number.isNaN(n) ? null : n);
  }

  ajouterOperation(): void {
    const type = this.compoType();
    const sid = this.compoStationId();
    const iid = this.compoItemId();
    const echelle = this.compoEchelle();
    let op: OperationBareme | null = null;
    if (type === 'EXCLURE_CRITERE') {
      if (iid == null) { this.compoErreur.set('Choisissez le critère à retirer.'); return; }
      op = { type, cibleItemId: iid, cibleStationId: null, nouvelleEchelle: null };
    } else if (type === 'EXCLURE_STATION') {
      if (sid == null) { this.compoErreur.set('Choisissez la station à exclure.'); return; }
      op = { type, cibleItemId: null, cibleStationId: sid, nouvelleEchelle: null };
    } else {
      if (echelle == null || echelle <= 0) { this.compoErreur.set('Indiquez la nouvelle échelle (> 0).'); return; }
      if (iid != null) op = { type, cibleItemId: iid, cibleStationId: null, nouvelleEchelle: echelle };
      else if (sid != null) op = { type, cibleItemId: null, cibleStationId: sid, nouvelleEchelle: echelle };
      else { this.compoErreur.set('Choisissez une station ou un critère à repondérer.'); return; }
    }
    this.compoErreur.set(null);
    this.composition.update((ops) => [...ops, op as OperationBareme]);
    this.projection.set(null); // l'effet affiché ne correspond plus à la composition : à re-prévisualiser
  }

  retirerOperation(index: number): void {
    this.composition.update((ops) => ops.filter((_, i) => i !== index));
    this.projection.set(null);
  }

  viderComposition(): void {
    this.composition.set([]);
    this.projection.set(null);
  }

  previsualiser(): void {
    if (this.projectionBusy()) return;
    this.projectionBusy.set(true);
    this.compoErreur.set(null);
    this.ai.projeter(this.examenIdNum(), this.composition()).subscribe({
      next: (proj) => {
        this.projectionBusy.set(false);
        this.projection.set(proj);
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.projectionBusy.set(false);
        this.compoErreur.set(this.messageErreur(err, 'Prévisualisation indisponible (module IA).'));
      },
    });
  }

  /** L'acte manuel : la composition devient la version suivante (vide = retour à l'origine). */
  appliquerComposition(): void {
    if (this.compoBusy()) return;
    const motif = this.compoMotif().trim();
    if (!motif) {
      this.compoActErreur.set('Le motif est obligatoire — la délibération se raconte (ADR-0030 D1).');
      return;
    }
    if (!this.projection()) {
      this.compoActErreur.set("Prévisualisez l'effet avant d'enregistrer — il ne se découvre jamais après coup (D10).");
      return;
    }
    this.compoBusy.set(true);
    this.compoActErreur.set(null);
    this.scoring.creerBaremeDeliberation(this.examenIdNum(), { motif, operations: this.composition() }).subscribe({
      next: (version) => {
        this.compoBusy.set(false);
        this.compositionOuverte.set(false);
        this.succes.set(`Barème de délibération v${version.version} enregistré.`);
        this.reload();
      },
      error: (err: { status?: number; error?: { message?: string } }) => {
        this.compoBusy.set(false);
        this.compoActErreur.set(this.messageErreur(err, 'Le barème n’a pas pu être enregistré.'));
      },
    });
  }
}
