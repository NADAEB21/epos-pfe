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
 *    avec un style visuellement distinct (jamais confondu avec une lecture) ;
 *  - code INCONNU (ai-service en avance sur cette version du site) → état
 *    DÉGRADÉ VISIBLE : le code brut + « lecture indisponible », style refus.
 *    lecture-indices.ts lève exprès sur un code inconnu (refus bruyant, D7) ;
 *    mais lever DANS un computed de template ferait tomber tout l'écran de
 *    délibération pour un seul indice — or afficher est une LECTURE, et une
 *    lecture se dégrade, elle ne casse pas l'écran hôte (ADR-0015). Dégradé
 *    visible ≠ silencieux : rien n'est inventé, le manque se voit.
 */
@Component({
  selector: 'app-lecture-indice',
  standalone: true,
  templateUrl: './lecture-indice.component.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LectureIndiceComponent {
  readonly indice = input<Indice | null>(null);

  private readonly lecture = computed(() => {
    const i = this.indice();
    if (!i) return null;
    try {
      return { libelle: libelleCode(i.code), texte: lireIndice(i), degrade: false };
    } catch {
      return {
        libelle: i.code,
        texte: 'lecture indisponible — indice non reconnu par cette version du site',
        degrade: true,
      };
    }
  });

  readonly libelle = computed(() => this.lecture()?.libelle ?? '');

  readonly texte = computed(() => this.lecture()?.texte ?? '');

  readonly estDegrade = computed(() => this.lecture()?.degrade ?? false);

  /** Pilote le style « pas une lecture » — refus du moteur OU code inconnu. */
  readonly estRefus = computed(
    () => this.estDegrade() || this.indice()?.statut === 'NON_CONCLUANT',
  );
}
