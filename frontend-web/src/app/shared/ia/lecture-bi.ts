/**
 * #365 (N10) — gabarits FRANÇAIS déterministes des lectures BI (ADR-0029 D6).
 * Même discipline que `lecture-indices.ts` / `lecture-deliberation.ts` :
 * fonctions PURES, refus BRUYANT sur un code inconnu — l'écran hôte attrape
 * (`lectureBiSure`) et dégrade visiblement, il ne casse pas.
 *
 * Les codes sont émis par `ai-service/app/bi.py` ; la `raison` backend
 * s'affiche VERBATIM à côté du libellé, jamais recomposée ici.
 */

export type CodeLectureBi =
  | 'AUCUN_EXAMEN_CLOS'
  | 'SANS_NOTATION'
  | 'COUVERTURE_INCOMPLETE'
  | 'EFFECTIF_INSUFFISANT';

/** Le libellé court d'un code. Lève sur un code inconnu. */
export function libelleLectureBi(code: string): string {
  switch (code as CodeLectureBi) {
    case 'AUCUN_EXAMEN_CLOS':
      return 'Aucune session close';
    case 'SANS_NOTATION':
      return 'Session sans notation';
    case 'COUVERTURE_INCOMPLETE':
      return 'Couverture du barème figé incomplète';
    case 'EFFECTIF_INSUFFISANT':
      return 'Effectif insuffisant';
    default:
      throw new Error(`Code de lecture BI inconnu côté web : "${code}" — mettre à jour lecture-bi.ts.`);
  }
}

/** La version « qui ne casse pas l'écran » : un code inconnu devient une lecture DÉGRADÉE, dite. */
export function lectureBiSure(code: string): { libelle: string; degrade: boolean } {
  try {
    return { libelle: libelleLectureBi(code), degrade: false };
  } catch {
    return { libelle: `${code} — lecture indisponible (code non reconnu par cette version du site)`, degrade: true };
  }
}

const NUM = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 1 });
const PCT = new Intl.NumberFormat('fr-FR', { maximumFractionDigits: 0 });

/** Ramène un total « x / d » à /20 (les deux lectures, ADR-0030 D4) ; null si rien à ramener. */
export function sur20(valeur: number | null | undefined, denominateur: number | null | undefined): number | null {
  if (valeur == null || denominateur == null || denominateur <= 0) return null;
  return (valeur / denominateur) * 20;
}

export function fmtNum(v: number | null | undefined): string {
  return v == null || Number.isNaN(v) ? '—' : NUM.format(v);
}

/** Un taux 0..1 → « 67 % » ; null → « — ». */
export function fmtTaux(t: number | null | undefined): string {
  return t == null || Number.isNaN(t) ? '—' : `${PCT.format(t * 100)} %`;
}

/** « 2026-06-10 » → « 10/06/2026 » ; toute autre forme est rendue telle quelle. */
export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return 'date inconnue';
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[3]}/${m[2]}/${m[1]}` : iso;
}
