import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Indice, libelleCode, lireIndice } from './lecture-indices';

/**
 * #360 (F4) — Atome de rendu pour UN indice IA/BI : libellé du code, lecture
 * ou refus (via lecture-indices.ts), effectif n.
 *
 * Même discipline que les composants F2 (spec-composants-graphes-f2.md,
 * §Règles transverses) : composant de RENDU PUR, aucun appel réseau — les
 * données arrivent par input, le calcul (ai-service) et la garde de refus
 * (ADR-0021 D2) restent le problème de l'écran hôte.
 *
 * États obligatoires :
 *  - indice absent/malformé → ne rend rien (jamais de valeur fabriquée,
 *    leçon du 403 avalé, ADR-0029 D7) ;
 *  - NON_CONCLUANT → seul le texte de refus s'affiche, jamais une valeur,
 *    avec un style visuellement distinct (jamais confondu avec une lecture).
 */
@Component({
  selector: 'app-lecture-indice',
  standalone: true,
  templateUrl: './lecture-indice.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LectureIndiceComponent {
  readonly indice = input<Indice | null>(null);

  readonly libelle = computed(() => {
    const i = this.indice();
    return i ? libelleCode(i.code) : '';
  });

  readonly texte = computed(() => {
    const i = this.indice();
    return i ? lireIndice(i) : '';
  });

  readonly estRefus = computed(() => this.indice()?.statut === 'NON_CONCLUANT');
}
