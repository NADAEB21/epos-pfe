import * as echarts from 'echarts/core';
import { BarChart } from 'echarts/charts';
import { GridComponent, TooltipComponent } from 'echarts/components';
import { CanvasRenderer } from 'echarts/renderers';

let registered = false;

/**
 * Enregistrement ECharts minimal (import core tree-shaken) — #356.
 *
 * Les deux composants de graphe ne dessinent QUE des barres (histogramme,
 * classement horizontal) : ni pie, ni line, ni carte, ni 3D. On n'enregistre
 * que ce qui sert, même discipline de bundle que l'ADR-0029 D7 côté ai-service
 * (budget mémoire/poste facultaire) — voir aussi la règle 5 de la spec N4
 * (« init ECharts en lazy, le chunk ne doit pas alourdir le bundle initial »).
 *
 * Idempotent : `echarts.use()` peut être rappelé sans effet de bord, mais on
 * évite quand même le travail redondant si les deux composants sont montés
 * ensemble (harnais, écran de délibération).
 */
export function registerGraphesEcharts(): void {
  if (registered) return;
  echarts.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);
  registered = true;
}
