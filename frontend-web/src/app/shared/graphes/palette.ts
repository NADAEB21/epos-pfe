/**
 * #405 — la palette des graphes, en UN endroit. Les composants ECharts ne
 * lisent pas Tailwind (canvas) : ces hex sont la copie des jetons de
 * `tailwind.config.js`. Changer la couleur de marque = changer ici ET là.
 */
export const GRAPH_PALETTE = {
  light: {
    nominal: '#1f5e3a', // brand
    nominalSoft: '#2d8050', // brand-light
    sousSeuil: '#f87171', // red-400
    track: '#f3f4f6', // gray-100
    text: '#374151', // gray-700
    muted: '#9ca3af', // gray-400
    axis: '#e5e7eb', // gray-200
  },
  dark: {
    nominal: '#2d8050', // brand-light — lisible sur fond sombre
    nominalSoft: '#8cc2a2', // brand-300
    sousSeuil: '#f87171',
    track: '#374151', // gray-700
    text: '#e5e7eb', // gray-200
    muted: '#9ca3af',
    axis: '#4b5563', // gray-600
  },
} as const;
