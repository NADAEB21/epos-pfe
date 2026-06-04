import { Component, computed, effect, inject, signal } from '@angular/core';
import { input } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ScoringApiService } from '../../core/api/scoring-api.service';
import { EtudiantSummary, ParticipationSummary } from '../../core/api/models';

/** A participation joined to its student — one roster row. */
interface RosterRow {
  participationId: number;
  etudiantId: number | null;
  nom: string;
  prenom: string;
  numeroInscription: string | null;
  numEchantillon: string | null;
  present: boolean | null;
  note: number | null;
}

/**
 * Étudiants tab — the per-exam roster (read-only for now). A student isn't tied
 * to an exam directly; the only link is scoring-service's ExamenParticipation,
 * so the roster of exam X is its participations (filtered server-side via
 * ?examenId) joined to the global student directory by etudiantId.
 *
 * Two-call initial load: listParticipations(examId) for the enrolments and
 * listEtudiants() to resolve ids → names. Présence/note are mostly empty before
 * the exam runs — they fill in once scoring starts (a later screen owns editing).
 */
@Component({
  selector: 'app-etudiants',
  standalone: true,
  template: `
    @if (loading()) {
      <div class="space-y-3 animate-pulse">
        <div class="h-10 rounded-lg bg-gray-200 w-1/3"></div>
        <div class="h-64 rounded-xl bg-gray-200"></div>
      </div>
    } @else if (error()) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Impossible de charger la liste des etudiants.</p>
        <p class="text-sm text-gray-500 mb-4">Verifiez votre connexion puis reessayez.</p>
        <button
          type="button"
          (click)="reload()"
          class="inline-flex items-center px-4 py-2 rounded-lg bg-brand text-white text-sm font-medium hover:bg-brand-dark transition-colors"
        >
          Reessayer
        </button>
      </div>
    } @else if (rows().length === 0) {
      <div class="rounded-xl bg-white border border-gray-200 p-8 text-center shadow-card">
        <p class="text-gray-700 mb-1">Aucun etudiant inscrit a cet examen.</p>
        <p class="text-sm text-gray-500">
          L'import de la liste des etudiants arrivera dans un prochain ecran. Pour
          l'instant, cet onglet affiche les inscrits et leur etat.
        </p>
      </div>
    } @else {
      <!-- summary -->
      <div class="flex flex-wrap items-center gap-x-6 gap-y-1 mb-4 text-sm">
        <span class="text-gray-900 font-semibold">{{ rows().length }} etudiant(s)</span>
        @if (presentsCount() > 0) {
          <span class="text-gray-500">{{ presentsCount() }} present(s)</span>
        }
        @if (absentsCount() > 0) {
          <span class="text-gray-500">{{ absentsCount() }} absent(s)</span>
        }
      </div>

      <div class="rounded-xl bg-white border border-gray-200 shadow-card overflow-hidden">
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-xs text-gray-400 border-b border-gray-100">
              <th class="px-4 py-2.5 font-medium w-8">#</th>
              <th class="px-4 py-2.5 font-medium">Etudiant</th>
              <th class="px-4 py-2.5 font-medium">N&deg; inscription</th>
              <th class="px-4 py-2.5 font-medium">N&deg; echantillon</th>
              <th class="px-4 py-2.5 font-medium">Presence</th>
              <th class="px-4 py-2.5 font-medium text-right">Note</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-50">
            @for (r of rows(); track r.participationId; let i = $index) {
              <tr class="hover:bg-surface">
                <td class="px-4 py-2.5 text-gray-400">{{ i + 1 }}</td>
                <td class="px-4 py-2.5 text-gray-800 font-medium">{{ displayName(r) }}</td>
                <td class="px-4 py-2.5 text-gray-600">{{ r.numeroInscription || '—' }}</td>
                <td class="px-4 py-2.5 text-gray-600">{{ r.numEchantillon || '—' }}</td>
                <td class="px-4 py-2.5">
                  @if (r.present === true) {
                    <span class="text-xs px-2 py-0.5 rounded-full bg-status-success text-white">Present</span>
                  } @else if (r.present === false) {
                    <span class="text-xs px-2 py-0.5 rounded-full bg-status-danger/10 text-status-danger">Absent</span>
                  } @else {
                    <span class="text-xs text-gray-400">—</span>
                  }
                </td>
                <td class="px-4 py-2.5 text-right text-gray-700">
                  {{ r.note != null ? r.note : '—' }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>

      <p class="text-xs text-gray-400 mt-3">
        Lecture seule — l'import et l'edition de la liste arriveront dans un prochain ecran.
      </p>
    }
  `,
})
export class EtudiantsComponent {
  private readonly scoring = inject(ScoringApiService);

  /** Inherited from the parent examens/:id route via withComponentInputBinding(). */
  readonly id = input.required<string>();

  readonly rows = signal<RosterRow[]>([]);
  readonly loading = signal(true);
  readonly error = signal(false);

  readonly presentsCount = computed(() => this.rows().filter((r) => r.present === true).length);
  readonly absentsCount = computed(() => this.rows().filter((r) => r.present === false).length);

  constructor() {
    effect(() => {
      const examId = Number(this.id());
      if (!Number.isFinite(examId)) {
        this.error.set(true);
        this.loading.set(false);
        return;
      }
      this.load(examId);
    });
  }

  reload(): void {
    this.load(Number(this.id()));
  }

  private load(examId: number): void {
    this.loading.set(true);
    this.error.set(false);
    forkJoin({
      participations: this.scoring.listParticipations(examId),
      etudiants: this.scoring.listEtudiants(),
    }).subscribe({
      next: ({ participations, etudiants }) => {
        this.rows.set(this.buildRows(participations, etudiants));
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  /** Join participations (enrolments) to the student directory, ordered by name. */
  private buildRows(
    participations: ParticipationSummary[],
    etudiants: EtudiantSummary[],
  ): RosterRow[] {
    const byId = new Map<number, EtudiantSummary>();
    for (const e of etudiants) byId.set(e.id, e);

    return participations
      .map((p) => {
        const e = p.etudiantId != null ? byId.get(p.etudiantId) : undefined;
        return {
          participationId: p.id,
          etudiantId: p.etudiantId,
          nom: e?.nom ?? '',
          prenom: e?.prenom ?? '',
          numeroInscription: e?.numero_inscription ?? null,
          numEchantillon: p.num_echantillon,
          present: p.est_present,
          note: p.note,
        };
      })
      .sort((a, b) => (a.nom || '').localeCompare(b.nom || '') || (a.prenom || '').localeCompare(b.prenom || ''));
  }

  /** Full name, or a stable fallback for an enrolment whose student row is missing. */
  displayName(r: RosterRow): string {
    const full = `${r.prenom} ${r.nom}`.trim();
    return full || (r.etudiantId != null ? `Etudiant #${r.etudiantId}` : 'Etudiant inconnu');
  }
}
