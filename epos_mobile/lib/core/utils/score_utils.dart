// lib/core/utils/score_utils.dart

import '../../features/grading/domain/entities/item_evaluation.dart';
import '../../features/grading/domain/entities/notation.dart';

class ScoreUtils {
  ScoreUtils._();

  /// Score = Σ (valeur_critère × pondération_critère)
  static double calculerScore({
    required List<ItemEvaluation> items,
    required Map<int, Notation>   notations,
  }) {
    double score = 0;
    for (final item in items) {
      final notation = notations[item.id];
      if (notation == null) continue;

      if (item.type == TypeCritere.binaire) {
        score += notation.valeur * item.ponderation;
      } else {
        score += notation.valeur.clamp(0.0, item.valeurMax);
      }
    }
    return score;
  }

  /// Conformité Station 4 : Teneur ∈ [90%, 110%]
  static bool estConforme(double masseExp, double masseTheorique) {
    if (masseTheorique == 0) return false;
    final teneur = (masseExp / masseTheorique) * 100;
    return teneur >= 90 && teneur <= 110;
  }

  /// Intervalle de tolérance ±10%
  static ({double min, double max}) intervalleTolerance(double masseTheorique) {
    return (
      min: masseTheorique * 0.90,
      max: masseTheorique * 1.10,
    );
  }

  /// Progression de complétion (0.0 → 1.0)
  static double progression({
    required List<ItemEvaluation> items,
    required Map<int, Notation>   notations,
  }) {
    if (items.isEmpty) return 0;
    final remplis = items.where((i) => notations.containsKey(i.id)).length;
    return remplis / items.length;
  }
}