// test/unit/score_utils_test.dart
//
// #310 — les ponderations decimales s'affichaient TRONQUEES.
//
// Le fichier etait VIDE (0 octet), donc compte parmi les echecs de base de
// `flutter test` (« pas de main() »). Il porte maintenant la regression de #310.
//
// Le defaut : l'IHM appelait `ponderation.toInt()`, qui TRONQUE. Un critere de
// 1,5 pt s'affichait « 1 pts », un de 3,5 « 3 pts », et un parent de 5 pts
// (1,5 + 3,5) totalisait « 4 pts ». Les donnees etaient justes depuis toujours —
// le JSON parse bien un `double` — seule la presentation mentait. Aucune note
// stockee n'a jamais ete fausse.

import 'package:flutter_test/flutter_test.dart';
import 'package:epos_mobile/core/utils/score_utils.dart';

void main() {
  group('ScoreUtils.fmtPoints — #310', () {
    test('un entier reste sans decimale (pas de « 5.0 pts »)', () {
      expect(ScoreUtils.fmtPoints(5), '5');
      expect(ScoreUtils.fmtPoints(5.0), '5');
      expect(ScoreUtils.fmtPoints(0), '0');
      expect(ScoreUtils.fmtPoints(20), '20');
    });

    test('LE defaut de #310 : un demi-point n\'est plus tronque', () {
      // Avant : `1.5.toInt()` => « 1 ».
      expect(ScoreUtils.fmtPoints(1.5), '1.5');
      // Avant : « 3 ».
      expect(ScoreUtils.fmtPoints(3.5), '3.5');
      // Et le parent 1,5 + 3,5 s'affichait « 4 » au lieu de « 5 ».
      expect(ScoreUtils.fmtPoints(1.5 + 3.5), '5');
    });

    test('les decimales autres que ,5 sont rendues a un chiffre', () {
      expect(ScoreUtils.fmtPoints(0.5), '0.5');
      expect(ScoreUtils.fmtPoints(2.25), '2.3'); // arrondi de toStringAsFixed(1)
      expect(ScoreUtils.fmtPoints(9.99), '10.0');
    });

    test('ne perd pas le signe negatif (score reajuste vers le bas)', () {
      expect(ScoreUtils.fmtPoints(-2), '-2');
      expect(ScoreUtils.fmtPoints(-1.5), '-1.5');
    });
  });
}
