// lib/core/utils/score_utils.dart

import '../../features/grading/domain/entities/item_evaluation.dart';
import '../../features/grading/domain/entities/notation.dart';

class ScoreUtils {
  ScoreUtils._();

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