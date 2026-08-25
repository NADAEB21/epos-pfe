/**
 * Chargement ECharts minimal (import core tree-shaken) — #356.
 *
 * Les deux composants de graphe ne dessinent QUE des barres (histogramme,
 * classement horizontal) : ni pie, ni line, ni carte, ni 3D. On n'enregistre
 * que ce qui sert, même discipline de bundle que l'ADR-0029 D7 côté ai-service
 * (budget mémoire/poste facultaire).
 *
 * ⚠️ AUCUN import statique d'echarts ici ou dans app.config : la règle 5 de la
 * spec N4 exige un init LAZY (« le chunk ne doit pas alourdir le bundle
 * initial »). Ce module ne contient que des imports DYNAMIQUES ; ngx-echarts
 * appelle `loadEcharts()` à la première directive `echarts` rencontrée, donc
 * le poids d'ECharts n'est payé que par les écrans qui dessinent un graphe.
 * Mesuré au fix : initial 782.84 kB (import statique) → 375.70 kB (lazy).
 */
export async function loadEcharts() {
  const [core, { BarChart }, { GridComponent, TooltipComponent }, { CanvasRenderer }] =
    await Promise.all([
      import('echarts/core'),
      import('echarts/charts'),
      import('echarts/components'),
      import('echarts/renderers'),
    ]);
  // `use()` est idempotent — pas de garde nécessaire si plusieurs appels.
  core.use([BarChart, GridComponent, TooltipComponent, CanvasRenderer]);
  return core;
}
