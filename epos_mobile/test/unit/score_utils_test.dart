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
      expect(ScoreUtils.fmtPoints(1.5), '1,5');
      // Avant : « 3 ».
      expect(ScoreUtils.fmtPoints(3.5), '3,5');
      // Et le parent 1,5 + 3,5 s'affichait « 4 » au lieu de « 5 ».
      expect(ScoreUtils.fmtPoints(1.5 + 3.5), '5');
    });

    // #417 (recette du 05/09) — le QUART DE POINT s'affiche tel qu'il est
    // stocké. Avant : toStringAsFixed(1) rendait 1,75 → « 1.8 » et 2,25 →
    // « 2.3 » : la note stockée était juste, l'écran mentait d'un dixième.
    test('#417 : le quart de point n\'est plus arrondi', () {
      expect(ScoreUtils.fmtPoints(1.75), '1,75');
      expect(ScoreUtils.fmtPoints(2.25), '2,25');
      expect(ScoreUtils.fmtPoints(0.25), '0,25');
      expect(ScoreUtils.fmtPoints(0.5), '0,5');
    });

    test('au-dela de deux decimales, arrondi a deux (jamais de « 20.0 »)', () {
      expect(ScoreUtils.fmtPoints(9.99), '9,99');
      expect(ScoreUtils.fmtPoints(9.999), '10');
      expect(ScoreUtils.fmtPoints(20.0), '20');
    });

    test('ne perd pas le signe negatif (score reajuste vers le bas)', () {
      expect(ScoreUtils.fmtPoints(-2), '-2');
      expect(ScoreUtils.fmtPoints(-1.5), '-1,5');
    });
  });
}
