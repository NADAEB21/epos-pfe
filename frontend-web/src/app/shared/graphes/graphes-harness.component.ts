import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { HistogrammeStationComponent, HistogrammeBin } from './histogramme-station.component';
import { BarresConcentrationComponent, BarreConcentrationLigne } from './barres-concentration.component';

/**
 * Harnais de vérification #356 — « type storybook » demandé par l'AC :
 * chaque composant rendu sur DONNÉES FIXES, vérifiable seul, sans backend.
 *
 * Monté sur `/dev/graphes` (app.routes.ts), gardé par `canMatch: isDevMode()` :
 * accessible en `ng serve`, 404 en build de prod.
 *
 * Couvre : le nominal, les deux états obligatoires de chaque composant,
 * et le thème clair/sombre (bascule en direct — même instance de graphe,
 * mêmes données, seule la palette change).
 */
@Component({
  selector: 'app-graphes-harness',
  standalone: true,
  imports: [HistogrammeStationComponent, BarresConcentrationComponent],
  templateUrl: './graphes-harness.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GraphesHarnessComponent {
  readonly theme = signal<'light' | 'dark'>('light');

  toggleTheme(): void {
    this.theme.update((t) => (t === 'light' ? 'dark' : 'light'));
  }

  // ---- HistogrammeStation ---------------------------------------------

  readonly binsNominal: HistogrammeBin[] = [
    { label: '0–4', count: 1, pct: 8, sousSeuil: true },
    { label: '4–8', count: 2, pct: 17, sousSeuil: true },
    { label: '8–12', count: 3, pct: 25, sousSeuil: false },
    { label: '12–16', count: 4, pct: 33, sousSeuil: false },
    { label: '16–20', count: 2, pct: 17, sousSeuil: false },
  ];
  readonly nNominal = 12;

  readonly binsVide: HistogrammeBin[] = [];
  readonly nVide = 0;

  // bins volontairement non défini pour illustrer l'état « malformé ».

  // ---- BarresConcentration ----------------------------------------------

  readonly lignesNominal: BarreConcentrationLigne[] = [
    { id: 1, label: 'St. 1 — Chimie', valeurPct: 75, n: 12 },
    { id: 2, label: 'St. 2 — Bota', valeurPct: 40, n: 10 },
    { id: 3, label: 'St. 3 — Galénique', valeurPct: 10, n: 9 },
    // Aucune notation verrouillée — pas de detail : repli par défaut.
    { id: 4, label: 'St. 4 — Toxico', valeurPct: null, n: 0 },
    // Indice psychométrique refusé — le texte de refus VERBATIM (ADR-0021 D2).
    {
      id: 5,
      label: 'St. 5 — Pharmaco',
      valeurPct: null,
      n: 0,
      detail: 'comparaison non concluante — effectif insuffisant',
    },
  ];
}
