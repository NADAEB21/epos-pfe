// lib/core/utils/score_utils.dart

import '../../features/grading/domain/entities/item_evaluation.dart';
import '../../features/grading/domain/entities/notation.dart';

class ScoreUtils {
  ScoreUtils._();

  /// Affichage d'un nombre de points en français : jusqu'à DEUX décimales,
  /// zéros inutiles retirés, virgule décimale. 2 → « 2 », 1,5 → « 1,5 »,
  /// 1,75 → « 1,75 ».
  ///
  /// #417 — avant, `toStringAsFixed(1)` arrondissait 1,75 en « 1.8 » : le
  /// quart de point, courant dans les grilles réelles (sous-critères à 0,25),
  /// était stocké exactement (REAL, aucun arrondi côté scoring) mais MENTI à
  /// l'écran. Un affichage qui arrondit un score est une erreur de mesure,
  /// pas un détail de présentation.
  static String fmtPoints(double v) {
    if (v == v.truncateToDouble()) return v.toInt().toString();
    final deux = v.toStringAsFixed(2)
        .replaceAll(RegExp(r'0+$'), '')   // 1.50 → 1.5 ; 10.00 → 10.
        .replaceAll(RegExp(r'\.$'), '');  // 10. → 10
    return deux.replaceAll('.', ',');
  }

  /// Un seul niveau de profondeur (#160) : seules les feuilles sont notées ;
  /// le score d'un critère parent = somme de ses sous-critères.
  static List<ItemEvaluation> feuilles(List<ItemEvaluation> items) {
    final result = <ItemEvaluation>[];
    for (final item in items) {
      if (item.hasSousCriteres) {
        result.addAll(item.sousCriteres);
      } else {
        result.add(item);
      }
    }
    return result;
  }

  static double calculerScore({
    required List<ItemEvaluation> items,
    required Map<int, Notation>   notations,
  }) {
    double score = 0;
    for (final item in feuilles(items)) {
      final notation = notations[item.id];
      if (notation == null) continue;
      score += item.type == TypeCritere.binaire
          ? notation.valeur * item.ponderation
          : notation.valeur.clamp(0.0, item.valeurMax);
    }
    return score;
  }

  /// Score cumulé des sous-critères d'un critère parent, pour l'affichage
  /// "Critère 1 : x/6" dans l'interface de notation.
  static double scoreCritere({
    required ItemEvaluation item,
    required Map<int, Notation> notations,
  }) {
    if (!item.hasSousCriteres) {
      final n = notations[item.id];
      if (n == null) return 0;
      return item.type == TypeCritere.binaire
          ? n.valeur * item.ponderation
          : n.valeur.clamp(0.0, item.valeurMax);
    }
    return calculerScore(items: item.sousCriteres, notations: notations);
  }

  static bool estConforme(double masseExp, double masseTheorique) {
    if (masseTheorique == 0) return false;
    final teneur = (masseExp / masseTheorique) * 100;
    return teneur >= 90 && teneur <= 110;
  }

  static ({double min, double max}) intervalleTolerance(double masseTheorique) {
    return (min: masseTheorique * 0.90, max: masseTheorique * 1.10);
  }

  static double progression({
    required List<ItemEvaluation> items,
    required Map<int, Notation>   notations,
  }) {
    final leaves = feuilles(items);
    if (leaves.isEmpty) return 0;
    final remplis = leaves.where((i) => notations.containsKey(i.id)).length;
    return remplis / leaves.length;
  }
}