/**
 * #360 (F4, épic IA/BI §3 T6) — Gabarits FR déterministes des lectures d'indices.
 *
 * ADR-0029 D6 : « un module qui parle à un jury de pharmacie ne doit jamais
 * pouvoir halluciner ». Ce fichier ne fait QUE deux choses, toutes deux
 * pilotées par seuil, jamais par génération de texte libre :
 *
 *   1. Pour un indice CONCLUANT, traduire sa valeur numérique en une phrase
 *      française lisible par un non-statisticien (« ce critère discrimine
 *      bien », jamais « r=0.42 »).
 *   2. Pour un indice NON_CONCLUANT, afficher tel quel le `raison` qu'
 *      ai-service a déjà produit (app/stats/engine.py, mêmes gabarits côté
 *      Python) — ce fichier ne reformule JAMAIS un refus. Le contrat de
 *      refus fait déjà partie du texte côté backend (ADR-0021 D2,
 *      ADR-0029 D6) ; le réécrire ici créerait deux sources de vérité pour
 *      la même phrase, et la première qui diverge ment à quelqu'un.
 *
 * ⚠️ Les SEUILS DE STATUT (n minimal pour passer CONCLUANT/NON_CONCLUANT,
 * variance nulle, barème sans valeur_max…) restent exclusivement dans
 * ai-service (`app/stats/engine.py`, constantes `SEUIL_N_*`). Ce fichier ne
 * les duplique pas et ne les devine pas : il lit `statut` tel quel et se
 * contente d'habiller la valeur quand elle existe.
 *
 * ⚠️ Les SEUILS DE LECTURE ci-dessous (bandes de magnitude — « discrimine
 * bien » vs « discrimine faiblement ») sont un ajout FRONT, absent de tout
 * ADR à ce jour. Ils s'INSPIRENT des conventions académiques usuelles de la
 * théorie classique des tests, adaptées pour rester lisibles à l'écran (pas
 * une reproduction exacte des barèmes cités — les bornes réelles sont dans
 * `SEUILS_LECTURE` ci-dessous, seule source de vérité ; le texte qui suit
 * les DÉCRIT, il ne les redéfinit pas) :
 *   - discrimination (inspiré d'Ebel & Frisbie) : < 0 discrimine à l'envers
 *     (alerte) · < 0,10 n'a séparé personne · 0,10–0,19 discrimine
 *     faiblement · 0,20–0,29 discrimine correctement · ≥ 0,30 discrimine
 *     bien ;
 *   - alpha de Cronbach (inspiré de George & Mallery, paliers « faible » et
 *     « discutable » d'origine fusionnés en un seul ici) : < 0 incohérente ·
 *     < 0,5 faible · 0,5–0,69 discutable · 0,7–0,79 acceptable · 0,8–0,89
 *     bonne · ≥ 0,9 excellente ;
 *   - difficulté (convention usuelle) : < 0,2 très difficile · 0,2–0,39
 *     difficile · 0,4–0,69 équilibrée · 0,7–0,84 facile · ≥ 0,85 très facile.
 * **À valider par Nada avant mise en production** (critère d'acceptation
 * #360) — c'est elle qui signe le barème et la lecture qui l'accompagne.
 * Les bandes sont exportées (`SEUILS_LECTURE`) pour être visibles en revue
 * et modifiables en un seul endroit — si vous changez une valeur là-bas,
 * mettez aussi ce commentaire à jour : c'est la fixture que la revue lit
 * en premier.
 */

import { IndiceAi } from '../../core/api/models';

export type StatutIndice = IndiceAi['statut'];

export type CodeIndice =
  | 'DIFFICULTE'
  | 'DISCRIMINATION'
  | 'ALPHA_CRONBACH'
  | 'CONCENTRATION_ECHEC'
  | 'SEVERITE_EVALUATEUR';

/**
 * Le miroir du JSON d'ai-service vit dans `core/api/models.ts` (`IndiceAi`,
 * snake_case à dessein — voir son commentaire). Ici on ne fait que le
 * RESSERRER : `code` devient l'union fermée des cinq indices que ce fichier
 * sait lire (les deux switch refusent bruyamment un sixième), et `details`
 * est typé `number` (les grandeurs annexes du moteur : `taux_autres`,
 * `p_value`, `k`, `moyenne_evaluateur`, `moyenne_autres`…). Un seul miroir,
 * deux vues — jamais deux déclarations qui peuvent diverger.
 */
export interface Indice extends IndiceAi {
  code: CodeIndice;
  details: Record<string, number>;
}

/** Le libellé du code, pour l'en-tête d'une carte ou d'une colonne. */
export function libelleCode(code: CodeIndice): string {
  switch (code) {
    case 'DIFFICULTE':
      return 'Difficulté';
    case 'DISCRIMINATION':
      return 'Discrimination';
    case 'ALPHA_CRONBACH':
      return 'Cohérence interne (α de Cronbach)';
    case 'CONCENTRATION_ECHEC':
      return "Concentration d'échec";
    case 'SEVERITE_EVALUATEUR':
      return 'Sévérité (comparaison intra-station)';
    default:
      // Refuse bruyamment plutôt que de rendre "undefined" à l'écran : un
      // nouveau code émis par ai-service sans mise à jour de ce fichier
      // doit se voir, pas se glisser en silence (même doctrine que le
      // "403 avalé", ADR-0029 D7).
      throw new Error(`Code d'indice inconnu côté web : "${code}" — mettre à jour lecture-indices.ts.`);
  }
}

/** Bandes de lecture — voir l'avertissement en tête de fichier (à valider par Nada). */
export const SEUILS_LECTURE = {
  difficulte: { tresDifficile: 0.2, difficile: 0.4, equilibreHaut: 0.7, facile: 0.85 },
  discrimination: { nulle: 0.1, faible: 0.2, correcte: 0.3 },
  alpha: { faible: 0.5, discutable: 0.7, acceptable: 0.8, bonne: 0.9 },
  concentration: { pValueSignificatif: 0.05 },
} as const;

function fmtPct(v: number): string {
  return `${Math.round(v * 100)} %`;
}

function fmtNum(v: number, decimales = 2): string {
  return v.toLocaleString('fr-FR', { minimumFractionDigits: decimales, maximumFractionDigits: decimales });
}

function fmtIc(ic: [number, number] | null, decimales = 2, pct = false): string {
  if (!ic) return '';
  const [lo, hi] = ic;
  const f = (x: number) => (pct ? fmtPct(x) : fmtNum(x, decimales));
  return ` (intervalle de confiance 95 % : ${f(lo)} à ${f(hi)})`;
}

// ── Une fiche par code — chacune une fonction PURE, seuil → phrase ─────────

function ficheDifficulte(indice: Indice): string {
  const p = indice.valeur as number;
  const s = SEUILS_LECTURE.difficulte;
  let lecture: string;
  if (p < s.tresDifficile) lecture = "très difficile — presque personne n'a réussi ce critère";
  else if (p < s.difficile) lecture = 'difficile — une minorité seulement a réussi';
  else if (p < s.equilibreHaut) lecture = 'difficulté équilibrée';
  else if (p < s.facile) lecture = 'facile — une large majorité a réussi';
  else lecture = "très facile — presque tout le monde a réussi, ce critère n'apporte guère d'information";
  return `Réussite : ${fmtPct(p)} des étudiants (${lecture}).${fmtIc(indice.ic, 0, true)}`;
}

function ficheDiscrimination(indice: Indice): string {
  const r = indice.valeur as number;
  const s = SEUILS_LECTURE.discrimination;
  let lecture: string;
  if (r < 0)
    lecture =
      "discrimine à l'envers — les étudiants faibles y réussissent mieux que les bons, signal d'alerte sur le critère";
  else if (r < s.nulle) lecture = "n'a séparé personne — ne distingue pas les bons étudiants des faibles";
  else if (r < s.faible) lecture = 'discrimine faiblement';
  else if (r < s.correcte) lecture = 'discrimine correctement';
  else lecture = 'discrimine bien';
  return `Discrimination ${fmtNum(r)} (${lecture}).${fmtIc(indice.ic)}`;
}

function ficheAlpha(indice: Indice): string {
  const a = indice.valeur as number;
  const s = SEUILS_LECTURE.alpha;
  const k = indice.details['k'];
  let lecture: string;
  if (a < 0) lecture = 'incohérente — cette grille ne mesure pas une chose unique, à revoir';
  else if (a < s.faible) lecture = 'faible';
  else if (a < s.discutable) lecture = 'discutable';
  else if (a < s.acceptable) lecture = 'acceptable';
  else if (a < s.bonne) lecture = 'bonne';
  else lecture = 'excellente (voire redondante — critères très proches les uns des autres)';
  const surK = k != null ? ` sur ${k} critère(s) notable(s)` : '';
  return `Cohérence interne ${fmtNum(a)}${surK} (${lecture}).${fmtIc(indice.ic)}`;
}

function ficheConcentration(indice: Indice): string {
  const taux = indice.valeur as number;
  const tauxAutres = indice.details['taux_autres'];
  const pValue = indice.details['p_value'];
  // Les deux doivent être connus pour affirmer un SENS (plus/moins) — sans
  // tauxAutres, dire "plus élevé" par défaut inventerait une comparaison.
  const significatif =
    pValue != null && tauxAutres != null && pValue < SEUILS_LECTURE.concentration.pValueSignificatif;
  const comparatif = significatif
    ? `taux d'échec significativement ${taux > tauxAutres! ? 'plus' : 'moins'} élevé que sur les autres stations (${fmtPct(tauxAutres!)})`
    : "taux d'échec qui ne se distingue pas statistiquement des autres stations";
  return `${fmtPct(taux)} d'échec — ${comparatif}.`;
}

function ficheSeverite(indice: Indice): string {
  const ecart = indice.valeur as number;
  const moyEval = indice.details['moyenne_evaluateur'];
  const moyAutres = indice.details['moyenne_autres'];
  const sens = ecart > 0 ? 'plus sévèrement' : ecart < 0 ? 'plus indulgemment' : 'ni plus ni moins sévèrement';
  const detail =
    moyEval != null && moyAutres != null
      ? ` (moyenne ${fmtNum(moyEval, 1)} contre ${fmtNum(moyAutres, 1)} pour les collègues de la même station)`
      : '';
  return `Note en moyenne ${sens} que ses collègues sur cette station${detail}.${fmtIc(indice.ic, 1)}`;
}

/**
 * Le point d'entrée unique. Ne reformule JAMAIS un refus (rend `raison` tel
 * quel, contrat ADR-0021 D2/ADR-0029 D6) ; produit une lecture pilotée par
 * seuil pour une valeur CONCLUANTE. Ne fabrique jamais une phrase sur du vide
 * — si l'indice est structurellement incomplet, rend une chaîne vide plutôt
 * qu'une exception qui casserait l'écran hôte.
 */
export function lireIndice(indice: Indice): string {
  if (indice.statut === 'NON_CONCLUANT') {
    return indice.raison ?? 'non concluant';
  }
  if (indice.valeur == null) {
    return '';
  }
  switch (indice.code) {
    case 'DIFFICULTE':
      return ficheDifficulte(indice);
    case 'DISCRIMINATION':
      return ficheDiscrimination(indice);
    case 'ALPHA_CRONBACH':
      return ficheAlpha(indice);
    case 'CONCENTRATION_ECHEC':
      return ficheConcentration(indice);
    case 'SEVERITE_EVALUATEUR':
      return ficheSeverite(indice);
    default:
      // Même garde qu'au-dessus, sur le second switch — les deux doivent
      // rester synchronisés si un sixième indice apparaît côté ai-service.
      throw new Error(`Code d'indice inconnu côté web : "${indice.code}" — mettre à jour lecture-indices.ts.`);
  }
}
