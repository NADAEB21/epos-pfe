import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ExamenWorkspaceStore } from '../workspace/examen-workspace.store';
import { LotSummary } from '../../../core/api/models';

interface PlanningLot {
  numeroLot: number;
  taille: number | null;
  statut: LotSummary['statut'];
  /** Back-to-back wave start WITHIN the day — same math as the Lots tab. */
  arrivee: string | null;
}

interface PlanningDay {
  /** yyyy-MM-dd */
  date: string;
  /** « samedi 2 août 2026 » */
  label: string;
  isExamDate: boolean;
  lots: PlanningLot[];
}

/**
 * Planning tab — the read-only multi-day recap (#147 / ADR-0011 leftover).
 *
 * One glance answers « qui passe quel jour, à quelle heure ? ». Purely derived
 * from the workspace store (exam + lots + station count) — no fetch of its own,
 * and NO editing: the « Jour de passage » field lives on each lot card in the
 * Lots tab, which stays the single place that writes. The arrival math is the
 * same formula as the Lots tab (heureDebut + rank-within-day · K · durée) so
 * the two screens cannot disagree.
 */
@Component({
  selector: 'app-planning',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './planning.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanningComponent {
  readonly store = inject(ExamenWorkspaceStore);

  readonly loading = this.store.prepLoading;
  readonly error = this.store.prepError;

  readonly days = computed<PlanningDay[]>(() => {
    const exam = this.store.exam();
    if (!exam) return [];
    const examDate = exam.dateExamen;

    const byDay = new Map<string, LotSummary[]>();
    for (const lot of this.store.lots()) {
      const day = lot.jour ?? examDate;
      if (!byDay.has(day)) byDay.set(day, []);
      byDay.get(day)!.push(lot);
    }

    return [...byDay.entries()]
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([date, lots]) => ({
        date,
        label: this.frLongDate(date),
        isExamDate: date === examDate,
        lots: [...lots]
          .sort((a, b) => (a.numeroLot ?? 0) - (b.numeroLot ?? 0))
          .map((lot, rangDansLeJour) => ({
            numeroLot: lot.numeroLot ?? 0,
            taille: lot.tailleLot,
            statut: lot.statut,
            arrivee: this.arrivee(rangDansLeJour),
          })),
      }));
  });

  readonly multiDay = computed(() => this.days().length > 1);

  /** Same formula as LotsComponent.arrivee — one convention, two readers. */
  private arrivee(rangDansLeJour: number): string | null {
    const e = this.store.exam();
    const heure = e?.heureDebut;
    const duree = e?.dureeStationMin;
    const k = this.store.stations().length;
    if (!heure || !duree || k === 0) return null;
    const [h, m] = heure.split(':').map(Number);
    if (Number.isNaN(h) || Number.isNaN(m)) return null;
    const total = h * 60 + m + rangDansLeJour * k * duree;
    const hh = Math.floor(total / 60) % 24;
    const mm = total % 60;
    return `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;
  }

  statutLabel(s: LotSummary['statut']): string | null {
    switch (s) {
      case 'EN_COURS':
        return 'En cours';
      case 'TERMINE':
        return 'Terminé';
      default:
        return null; // EN_ATTENTE is the normal setup state — showing it is noise
    }
  }

  private frLongDate(iso: string): string {
    const [y, m, d] = iso.split('-').map(Number);
    if (!y || !m || !d) return iso;
    const label = new Intl.DateTimeFormat('fr-FR', {
      weekday: 'long',
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    }).format(new Date(y, m - 1, d));
    return label.charAt(0).toUpperCase() + label.slice(1);
  }
}
