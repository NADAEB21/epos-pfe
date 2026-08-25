import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgxEchartsDirective } from 'ngx-echarts';
import { EChartsOption } from 'echarts';
import { registerGraphesEcharts } from './echarts-setup';

registerGraphesEcharts();

/** Une ligne du classement — une station (délibération) ou toute autre
 *  entité classée à l'horizontale (BI, N10). */
export interface BarreConcentrationLigne {
  id: number;
  label: string;
  /** `null` = rien à lire pour cette ligne (voir les états obligatoires). */
  valeurPct: number | null;
  n: number;
  /**
   * Optionnel, deux usages selon le contexte — jamais les deux à la fois :
   *  - `valeurPct` renseigné → un complément affiché en tooltip ;
   *  - `valeurPct === null` → le texte de refus VERBATIM du backend
   *    (ADR-0021 D2 / ADR-0029 D6, ex. « comparaison non concluante —
   *    effectif insuffisant »), rendu tel quel, jamais recomposé ici.
   *    Absent dans ce cas → repli sur « aucune notation verrouillée »
   *    (le cas délibération d'origine, #355).
   */
  detail?: string;
}

const PALETTE = {
  light: { bar: '#1f5e3a', track: '#f3f4f6', text: '#374151', muted: '#9ca3af' },
  dark: { bar: '#2d8050', track: '#374151', text: '#e5e7eb', muted: '#9ca3af' },
} as const;

const DEFAULT_ABSENT = 'aucune notation verrouillée';

/**
 * `BarresConcentration` (#356) — le classement des stations par taux d'échec
 * (délibération, ADR-0021 D4) et, demain, tout classement à barres
 * horizontales du BI (N10).
 *
 * Composant de RENDU PUR — mêmes règles que `HistogrammeStation` : aucun
 * appel réseau, aucune donnée fabriquée.
 *
 * États obligatoires (spec N4) :
 *  - `valeurPct === null` → la ligne s'affiche SANS barre remplie, avec la
 *    mention (voir `detail` ci-dessus) — la station muette reste VISIBLE,
 *    jamais masquée.
 *  - Une valeur n'est jamais affichée sans son `n` (« 50 % (sur 2) »).
 *  - `lignes` absent/malformé → rien du tout.
 */
@Component({
  selector: 'app-barres-concentration',
  standalone: true,
  imports: [NgxEchartsDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (!isMalformed()) {
      <div
        role="img"
        [attr.aria-label]="titreValeur()"
        echarts
        [options]="chartOptions()"
        [style.height.px]="hauteur()"
        class="w-full"
        (chartClick)="onClick($event)"
      ></div>
    }
  `,
})
export class BarresConcentrationComponent {
  readonly lignes = input<BarreConcentrationLigne[] | null | undefined>();
  /** Ce que la barre mesure (ex. « Taux d'échec »). */
  readonly titreValeur = input<string>('Valeur');
  readonly theme = input<'light' | 'dark'>('light');

  readonly ligneClick = output<number>();

  readonly isMalformed = computed(() => !Array.isArray(this.lignes()));

  /** ~34px par ligne + marges — pas de hauteur figée, le classement peut grandir. */
  readonly hauteur = computed(() => Math.max(72, (this.lignes()?.length ?? 0) * 34 + 16));

  /**
   * ECharts empile ses catégories du bas vers le haut par défaut ; on
   * inverse pour lire les lignes dans l'ORDRE D'ENTRÉE, de haut en bas —
   * l'ordre que l'écran hôte a déjà choisi (ex. délibération : taux
   * d'échec décroissant).
   */
  private readonly ordered = computed(() => [...(this.lignes() ?? [])].reverse());

  readonly chartOptions = computed<EChartsOption>(() => {
    const ordered = this.ordered();
    const p = this.theme() === 'dark' ? PALETTE.dark : PALETTE.light;
    return {
      backgroundColor: 'transparent',
      grid: { left: 8, right: 96, top: 4, bottom: 4, containLabel: true },
      tooltip: {
        trigger: 'item',
        formatter: (params: { dataIndex: number }) => {
          const l = ordered[params.dataIndex];
          if (!l) return '';
          const corps =
            l.valeurPct === null
              ? l.detail || DEFAULT_ABSENT
              : `${this.formatPct(l.valeurPct)}\u202f% (sur ${l.n})${l.detail ? '<br/>' + l.detail : ''}`;
          return `${l.label}<br/>${corps}`;
        },
      },
      xAxis: { type: 'value', show: false, max: 100 },
      yAxis: {
        type: 'category',
        data: ordered.map((l) => l.label),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: p.text, fontSize: 12, width: 110, overflow: 'truncate' },
      },
      series: [
        {
          type: 'bar',
          data: ordered.map((l) => ({
            value: l.valeurPct ?? 0,
            // Ligne « valeurPct null » : barre transparente — la piste grise
            // (showBackground) reste visible, sans être un jugement chiffré.
            itemStyle: { color: l.valeurPct === null ? 'transparent' : p.bar, borderRadius: 2 },
          })),
          barMaxWidth: 16,
          showBackground: true,
          backgroundStyle: { color: p.track, borderRadius: 2 },
          label: {
            show: true,
            position: 'right',
            color: p.muted,
            fontSize: 11,
            formatter: (params: { dataIndex: number }) => {
              const l = ordered[params.dataIndex];
              if (!l) return '';
              return l.valeurPct === null ? l.detail || DEFAULT_ABSENT : `${this.formatPct(l.valeurPct)}\u202f% (sur ${l.n})`;
            },
          },
        },
      ],
    }as EChartsOption;
  });

  /** `ligneClick(id)` (spec N4). */
  onClick(event: { dataIndex?: number }): void {
    const idx = event?.dataIndex;
    if (typeof idx !== 'number') return;
    const l = this.ordered()[idx];
    if (l) this.ligneClick.emit(l.id);
  }

  /** Jamais de pourcentage sans son n (règle transverse 2) — 1 décimale max. */
  private formatPct(v: number): string {
    return Number.isInteger(v) ? String(v) : v.toFixed(1);
  }
}
