import { ChangeDetectionStrategy, Component, computed, input, output } from '@angular/core';
import { NgxEchartsDirective } from 'ngx-echarts';
// Type-only : effacé à la compilation — le VRAI chargement d'ECharts (et
// l'enregistrement des modules) passe par la fabrique lazy de app.config
// (echarts-setup.loadEcharts, spec N4 règle 5). Aucun import statique ici.
import type { EChartsOption } from 'echarts';

/**
 * Un bac déjà calculé par l'écran hôte (`resultats.component.ts`, méthode
 * `binsFor`) — le composant NE recalcule JAMAIS une distribution, il rend
 * ce qu'on lui donne (spec N4, règle transverse 4).
 */
export interface HistogrammeBin {
  label: string;
  count: number;
  pct: number;
  sousSeuil: boolean;
}

/** Jetons Tailwind du projet (tailwind.config.js) — palette figée ici, pas de
 *  thème ECharts par défaut (spec N4, règle transverse 1). */
const PALETTE = {
  light: {
    nominal: '#1f5e3a', // brand
    sousSeuil: '#f87171', // red-400
    track: '#f3f4f6', // gray-100
    text: '#374151', // gray-700
    muted: '#9ca3af', // gray-400
    axis: '#e5e7eb', // gray-200
  },
  dark: {
    nominal: '#2d8050', // brand-light — le vert nominal reste lisible sur fond sombre
    sousSeuil: '#f87171', // red-400 — inchangé, déjà assez saturé
    track: '#374151', // gray-700
    text: '#e5e7eb', // gray-200
    muted: '#9ca3af', // gray-400
    axis: '#4b5563', // gray-600
  },
} as const;

/**
 * `HistogrammeStation` (#356) — la distribution des notes verrouillées d'une
 * station (délibération, `resultats.component.html`) ou d'un agrégat (BI, N10).
 *
 * Composant de RENDU PUR : aucun appel réseau, aucun recalcul de `bins` —
 * qui les calcule (scoring pour la délibération, ai-service pour les
 * indices) est le problème de l'écran hôte (spec N4, règle transverse 4).
 *
 * États obligatoires (spec N4) :
 *  - `n === 0`             → message d'état vide, JAMAIS un graphe vide.
 *  - `bins` absent/malformé → rien du tout (aucune donnée fabriquée —
 *    leçon du 403 avalé, ADR-0029 D7). Ces deux états sont volontairement
 *    DISTINCTS : n=0 est une mesure honnête (« rien à lire »), un `bins`
 *    absent est une entrée cassée (« je ne sais pas quoi rendre »).
 */
@Component({
  selector: 'app-histogramme-station',
  standalone: true,
  imports: [NgxEchartsDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (isMalformed()) {
      <!-- Entrée absente/malformée : rien du tout (spec N4). -->
    } @else if (n() === 0) {
      <p class="text-sm text-gray-500 italic py-3">
        Aucune notation verrouillée — pas de distribution à lire.
      </p>
    } @else {
      <div class="w-full">
        <div class="flex items-baseline justify-between mb-1">
          <span class="text-xs text-gray-400">{{ seuilLabel() }}</span>
          <span class="text-xs text-gray-500 tabular-nums">n = {{ n() }}</span>
        </div>
        <div
          role="img"
          [attr.aria-label]="ariaLabel()"
          echarts
          [options]="chartOptions()"
          class="h-40 w-full"
          (chartClick)="onClick($event)"
        ></div>
      </div>
    }
  `,
})
export class HistogrammeStationComponent {
  /** Bacs déjà calculés — voir {@link HistogrammeBin}. */
  readonly bins = input<HistogrammeBin[] | null | undefined>();
  /** Effectif (les notes verrouillées). Toujours affiché à côté du graphe. */
  readonly n = input<number>(0);
  /** Libellé du seuil, affiché tel quel (ex. « échec = note < 50 % du barème »). */
  readonly seuilLabel = input<string>('');
  /** Natif clair/sombre — voir la palette ci-dessus. */
  readonly theme = input<'light' | 'dark'>('light');

  readonly binClick = output<string>();

  readonly isMalformed = computed(() => !Array.isArray(this.bins()));

  readonly ariaLabel = computed(() => `Distribution des notes${this.seuilLabel() ? ' — ' + this.seuilLabel() : ''}`);

  readonly chartOptions = computed<EChartsOption>(() => {
    const bins = this.bins() ?? [];
    const p = this.theme() === 'dark' ? PALETTE.dark : PALETTE.light;
    return {
      backgroundColor: 'transparent',
      grid: { left: 32, right: 12, top: 20, bottom: 24 },
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        valueFormatter: (v: unknown) => `${v}`,
      },
      xAxis: {
        type: 'category',
        data: bins.map((b) => b.label),
        axisLine: { lineStyle: { color: p.axis } },
        axisLabel: { color: p.muted, fontSize: 11 },
        axisTick: { show: false },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: p.axis } },
        axisLabel: { color: p.muted, fontSize: 11 },
      },
      series: [
        {
          type: 'bar',
          data: bins.map((b) => ({
            value: b.count,
            itemStyle: {
              color: b.sousSeuil ? p.sousSeuil : p.nominal,
              borderRadius: [2, 2, 0, 0],
            },
          })),
          barMaxWidth: 28,
          showBackground: true,
          backgroundStyle: { color: p.track, borderRadius: [2, 2, 0, 0] },
          label: {
            show: true,
            position: 'top',
            color: p.text,
            fontSize: 11,
            formatter: (params: { value: number }) => (params.value > 0 ? String(params.value) : ''),
          },
        },
      ],
    }as EChartsOption;
  });

  /** `binClick(label)` — la délibération s'en sert pour filtrer/ouvrir (spec N4). */
  onClick(event: { name?: unknown }): void {
    const label = event?.name;
    if (typeof label === 'string') this.binClick.emit(label);
  }
}
