// integration_test/grading_save_test.dart
//
// LE CHEMIN D'ÉCRITURE, piloté depuis l'UI — jamais fait jusqu'ici.
//
// « L'écran de notation s'affiche » n'est PAS « on peut noter ». Session 21 a
// vérifié l'écriture au curl uniquement ; session 22 a rendu l'écran mais avec
// « Score /20 : 0 », donc aucune note n'a jamais transité par l'interface.
//
// C'est le chemin STRICT d'ADR-0015 : côté écriture on échoue FORT, parce
// qu'une note fausse persistée est un dommage permanent. C'est donc le chemin
// qui mérite le plus d'être piloté pour de vrai.
//
// Grille 5 (vérifiée en base) : items 17/18 BINAIRE (5 pts), 19 NUMERIQUE (max 6),
// 20 NUMERIQUE (max 4) — 4 feuilles, 20 points, 2 étudiants.
//
// La VÉRIFICATION FINALE est en SQL, dans run_scenario.sh, avant le restore :
// un test Dart dans Chrome ne peut pas interroger la base. Le test dépose la
// donnée et prouve que l'UI a accepté ; le shell prouve qu'elle a persisté.
//
// Lancement : bash integration_test/run_scenario.sh grading-save

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:epos_mobile/main.dart' as app;

List<String> _textesRendus(WidgetTester tester) {
  final out = <String>{};
  for (final w in tester.allWidgets) {
    if (w is Text) {
      final t = w.data;
      if (t != null && t.trim().isNotEmpty) out.add(t.trim());
    } else if (w is RichText) {
      final t = w.text.toPlainText().trim();
      if (t.isNotEmpty) out.add(t);
    }
  }
  return out.toList();
}

Future<bool> _attendre(WidgetTester tester, Finder f,
    {Duration limite = const Duration(seconds: 30)}) async {
  final fin = DateTime.now().add(limite);
  while (DateTime.now().isBefore(fin)) {
    await tester.pump(const Duration(milliseconds: 250));
    if (f.evaluate().isNotEmpty) return true;
  }
  return false;
}

/// Laisse passer du temps RÉEL (les requêtes réseau ne se résolvent pas sur des
/// pumps : `tester.pump` planifie une frame, il n'avance pas l'horloge).
Future<void> _patienter(WidgetTester tester, Duration d) async {
  final fin = DateTime.now().add(d);
  while (DateTime.now().isBefore(fin)) {
    await tester.pump(const Duration(milliseconds: 100));
  }
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('notation — saisie puis validation de groupe, depuis l\'UI',
      (tester) async {
    app.main();
    await tester.pump(const Duration(seconds: 2));

    // ── entrée (session persistée ou login) ──────────────────────────────
    final champLogin = find.byType(TextField);
    final accueil    = find.textContaining('Bonjour');
    final finAuth = DateTime.now().add(const Duration(seconds: 45));
    String entree = 'aucune (timeout)';
    while (DateTime.now().isBefore(finAuth)) {
      await tester.pump(const Duration(milliseconds: 250));
      if (accueil.evaluate().isNotEmpty)    { entree = 'session déjà active'; break; }
      if (champLogin.evaluate().isNotEmpty) { entree = 'écran de login';      break; }
    }
    if (entree == 'écran de login') {
      await tester.enterText(champLogin.at(0), 'eval@epos.tn');
      await tester.enterText(champLogin.at(1), 'Eval@1234');
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.byType(ElevatedButton).first);
      expect(await _attendre(tester, accueil, limite: const Duration(seconds: 45)),
          isTrue, reason: 'accueil jamais atteint (CORS ? --web-port=4300)');
    }
    debugPrint('╣ ENTRÉE : $entree');

    // ── atteindre l'écran de notation ────────────────────────────────────
    // find.text, PAS widgetWithText(ElevatedButton) : `.icon` construit un
    // sous-type privé que `find.byType` (type exact) ne matche pas.
    final btn = find.text('Reprendre la notation');
    expect(await _attendre(tester, btn, limite: const Duration(seconds: 75)), isTrue,
        reason: 'aucune session notable — rien à noter. Textes : ${_textesRendus(tester)}');
    await tester.tap(btn);
    await tester.pump(const Duration(milliseconds: 500));

    final marqueur =
        find.byWidgetPredicate((w) => w is Text && (w.data ?? '').contains('étudiants'));
    expect(await _attendre(tester, marqueur, limite: const Duration(seconds: 45)), isTrue,
        reason: 'écran de notation jamais rendu. Si 503 sur /stations/{id}/grille : #244. '
            'Textes : ${_textesRendus(tester)}');

    // ── SAISIE des cellules numériques ───────────────────────────────────
    // Les items BINAIRE sont des GestureDetector (non ciblés ici, trop
    // ambigus à isoler) ; les NUMERIQUE sont des TextField. On saisit « 3 »
    // partout : 3 ≤ max des deux items (6 et 4), donc aucune valeur n'est
    // rognée par _DecimalFormatter et le test ne dépend pas de l'ordre des
    // colonnes/lignes.
    final cellules = find.byType(TextField);
    final n = cellules.evaluate().length;
    debugPrint('╣ CELLULES numériques trouvées : $n');
    expect(n, greaterThan(0),
        reason: 'aucune cellule de saisie — la grille ne s\'est pas construite.');

    for (var i = 0; i < n; i++) {
      await tester.enterText(cellules.at(i), '3');
      await tester.pump(const Duration(milliseconds: 120));
    }
    // Laisser partir les requêtes de sauvegarde par cellule.
    await _patienter(tester, const Duration(seconds: 3));

    final apresSaisie = _textesRendus(tester);
    debugPrint('╣ APRÈS SAISIE — score affiché : '
        '${apresSaisie.where((t) => RegExp(r'^\d+([.,]\d+)?$').hasMatch(t)).join(" / ")}');

    // ── VALIDATION du groupe ─────────────────────────────────────────────
    final btnValider = find.text('Valider groupe');
    expect(btnValider.evaluate().isNotEmpty, isTrue,
        reason: 'bouton « Valider groupe » absent. Textes : $apresSaisie');
    await tester.tap(btnValider);
    await _patienter(tester, const Duration(seconds: 1));

    // Un dialogue « Étudiants non validés » peut s'interposer (les cellules
    // binaires sont restées vides) : on confirme explicitement.
    final confirmer = find.text('Confirmer');
    if (confirmer.evaluate().isNotEmpty) {
      debugPrint('╣ DIALOGUE « Étudiants non validés » → Confirmer');
      await tester.tap(confirmer);
    } else {
      debugPrint('╣ pas de dialogue de confirmation');
    }
    await _patienter(tester, const Duration(seconds: 4));

    final fin = _textesRendus(tester);
    debugPrint('╔══ APRÈS VALIDATION — ${fin.length} textes');
    for (final t in fin) {
      debugPrint('║  ${t.length > 90 ? t.substring(0, 90) : t}');
    }
    debugPrint('╚══');

    // Garde : une erreur visible invalide la suite (le SQL dirait « 0 ligne »
    // et on croirait à un simple échec d'écriture, pas à un refus de l'UI).
    final erreurs = fin.where((t) =>
        t.toLowerCase().contains('impossible') ||
        t.toLowerCase().contains('erreur')).toList();
    expect(erreurs, isEmpty,
        reason: 'l\'UI a signalé une erreur pendant la saisie/validation : $erreurs');

    debugPrint('╣ UI OK — la persistance est vérifiée en SQL par run_scenario.sh');
  });
}
