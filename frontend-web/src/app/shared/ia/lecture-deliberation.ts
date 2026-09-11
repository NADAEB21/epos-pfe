import { DeclencheurAi, OperationBareme } from '../../core/api/models';

/**
 * #363 (N9) — gabarits FRANÇAIS déterministes des propositions de délibération
 * (ADR-0029 D6 : « un module qui parle à un jury ne doit jamais halluciner »).
 * Même discipline que `lecture-indices.ts` (F4) : fonctions PURES, seuil →
 * phrase, et refus BRUYANT sur un code inconnu — l'écran hôte attrape et
 * dégrade visiblement (leçon PR #385), il ne casse pas.
 *
 * Deux familles de codes, émises par `ai-service/app/bareme/propositions.py` :
 *  - `lecture_code` d'une PROPOSITION (une opération D8 est suggérée) ;
 *  - `code` d'une LECTURE SANS PROPOSITION (le silence est dit — la `raison`
 *    backend s'affiche VERBATIM en plus du libellé).
 */

export type CodeProposition = 'CRITERE_IMPOSSIBLE' | 'CRITERE_SANS_LIEN' | 'STATION_EN_ECHEC';

export type CodeSansProposition =
  | 'GRILLE_INCOHERENTE'
  | 'STATION_NON_SNAPSHOTEE'
  | 'COUVERTURE_INCOMPLETE'
  | 'CIBLE_NON_DELIBERABLE'
  | 'REPONDERATION_JAMAIS_AUTOMATIQUE';

export type CodeLectureDeliberation = CodeProposition | CodeSansProposition;

/** Le libellé court d'un code (en-tête de carte). Lève sur un code inconnu. */
export function libelleLecture(code: string): string {
  switch (code as CodeLectureDeliberation) {
    case 'CRITERE_IMPOSSIBLE':
      return 'Critère impossible';
    case 'CRITERE_SANS_LIEN':
      return 'Critère sans lien avec le reste';
    case 'STATION_EN_ECHEC':
      return 'Station en échec';
    case 'GRILLE_INCOHERENTE':
      return 'Grille incohérente';
    case 'STATION_NON_SNAPSHOTEE':
      return 'Station sans barème figé';
    case 'COUVERTURE_INCOMPLETE':
      return 'Couverture du barème figé incomplète';
    case 'CIBLE_NON_DELIBERABLE':
      return 'Cible non délibérable';
    case 'REPONDERATION_JAMAIS_AUTOMATIQUE':
      return 'Repondération : jamais proposée automatiquement';
    default:
      throw new Error(
        `Code de délibération inconnu côté web : "${code}" — mettre à jour lecture-deliberation.ts.`,
      );
  }
}

/** La lecture d'une proposition : l'observation, puis la suggestion (ADR-0021 D8/D10). Lève sur un code inconnu. */
export function phraseProposition(code: string): string {
  switch (code as CodeProposition) {
    case 'CRITERE_IMPOSSIBLE':
      return "Personne, ou presque, n'a pu marquer sur ce critère : la faute est celle de l'instrument, pas des candidats. Suggestion : le retirer du barème et renormaliser.";
    case 'CRITERE_SANS_LIEN':
      return "Ce critère n'a séparé personne — les bons et les faibles y réussissent pareil, alors que le reste de la grille est cohérent. Suggestion : le retirer du barème et renormaliser.";
    case 'STATION_EN_ECHEC':
      return "Cette station met en échec la majorité des candidats, nettement plus que les autres stations. Suggestion : l'exclure du barème.";
    default:
      throw new Error(
        `Code de proposition inconnu côté web : "${code}" — mettre à jour lecture-deliberation.ts.`,
      );
  }
}

/** Nombre en français (virgule), `—` si absent. */
export function fmtNum(x: number | null | undefined, decimales = 2): string {
  if (x == null || Number.isNaN(x)) return '—';
  return x.toLocaleString('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: decimales });
}

/** Fraction 0–1 → « 56 % ». */
export function fmtPct(fraction: number | null | undefined): string {
  if (fraction == null || Number.isNaN(fraction)) return '—';
  return `${Math.round(fraction * 100)} %`;
}

/** Le déclencheur chiffré, lisible : indice, valeur, seuil (de NOTRE choix), sens. */
export function libelleDeclencheur(d: DeclencheurAi): string {
  const v = d.valeur;
  switch (d.code) {
    case 'DIFFICULTE':
      return `difficulté p = ${fmtNum(v, 3)} (seuil ≤ ${fmtNum(d.seuil, 2)}) — taux de réussite du critère`;
    case 'DISCRIMINATION':
      return `discrimination r = ${fmtNum(v, 3)} (seuil |r| ≤ ${fmtNum(d.seuil, 2)}) — corrélation au reste de la grille`;
    case 'ALPHA_CRONBACH':
      return `cohérence de la grille α = ${fmtNum(v, 2)} (≥ ${fmtNum(d.seuil, 2)}) — le total de référence est fiable`;
    case 'CONCENTRATION_ECHEC': {
      const p = typeof d['p_value'] === 'number' ? (d['p_value'] as number) : null;
      const autres = typeof d['taux_autres'] === 'number' ? (d['taux_autres'] as number) : null;
      return `taux d'échec ${fmtPct(v)} (seuil ≥ ${fmtPct(d.seuil)}) contre ${fmtPct(autres)} ailleurs, p = ${fmtNum(p, 4)}`;
    }
    default:
      return `${d.code} = ${fmtNum(v, 3)} (seuil ${fmtNum(d.seuil, 2)})`;
  }
}

/** Résolution des cibles vers des noms lisibles — fournie par l'écran hôte. */
export interface NomsCibles {
  station: (stationId: number) => string;
  critere: (itemId: number) => string;
}

/** Une opération du barème, en français, avec ses cibles nommées. */
export function libelleOperation(op: OperationBareme, noms: NomsCibles): string {
  switch (op.type) {
    case 'EXCLURE_CRITERE':
      return `Retirer le critère « ${noms.critere(op.cibleItemId ?? -1)} » du barème (renormalisation)`;
    case 'EXCLURE_STATION':
      return `Exclure la station « ${noms.station(op.cibleStationId ?? -1)} » du barème`;
    case 'REPONDERER':
      return op.cibleItemId != null
        ? `Repondérer le critère « ${noms.critere(op.cibleItemId)} » vers /${fmtNum(op.nouvelleEchelle, 2)}`
        : `Repondérer la station « ${noms.station(op.cibleStationId ?? -1)} » vers /${fmtNum(op.nouvelleEchelle, 2)}`;
    default:
      throw new Error(`Type d'opération inconnu côté web : "${String(op.type)}".`);
  }
}

/** « x / d » brut et l'équivalent /20 — les deux lectures honnêtes (ADR-0030 D4). */
export function sur20(valeur: number | null | undefined, denominateur: number | null | undefined): string {
  if (valeur == null || denominateur == null || denominateur <= 0) return '—';
  return `${fmtNum(valeur, 1)} / ${fmtNum(denominateur, 0)} (≈ ${fmtNum((valeur / denominateur) * 20, 1)} /20)`;
}
