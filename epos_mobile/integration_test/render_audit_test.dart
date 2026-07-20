// integration_test/render_audit_test.dart
//
// RENDER AUDIT — l'assertion qui GÉNÉRALISE.
//
// Les assertions au niveau propriété (« tempsRestant < X ») ne peuvent
// structurellement pas voir un mauvais LIBELLÉ : c'est ainsi que « Station 5 »
// a été manqué en session 20 alors même que l'écran concerné était piloté en
// live. Ce test ne prédit rien : il vide tous les textes réellement rendus et
// échoue sur des MOTIFS de repli. Une seule passe attrape les quatre symptômes
// connus (`Station N`, `null`, `N/A`, `Lot X/0`) — et ceux que personne n'a
// encore vus.
//
// Lancement : bash integration_test/run_scenario.sh render-audit
//   (le scénario coupe exam-service APRÈS avoir chauffé le snapshot ADR-0015,
//    pour observer ce que voit vraiment l'évaluateur pendant une panne)

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:epos_mobile/main.dart' as app;

/// Motifs de valeur-repli qui ne doivent JAMAIS atteindre l'écran.
/// Un seul jeu de règles pour toute l'UI : la fuite de placeholder est UNE
/// classe de bug, pas quatre bugs distincts.
final _interdits = <RegExp, String>{
  RegExp(r'^Station \d+$'): 'nom de station de repli (ADR-0015 : supprimé côté serveur)',
  RegExp(r'\bnull\b'): 'littéral « null » rendu',
  RegExp(r'\bNaN\b'): 'NaN rendu',
  RegExp(r'N/A'): 'placeholder « N/A »',
  RegExp(r'\b\d+/0\b'): 'compteur X/0 (dénominateur nul)',
};

/// Tous les textes visibles de l'arbre de widgets.
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

/// Attend qu'un finder apparaisse SANS pumpAndSettle : l'app a des timers
/// périodiques (refresh dashboard, compte à rebours), l'arbre n'est jamais
/// quiescent et pumpAndSettle brûlerait tout son timeout (11 min observées).
Future<bool> _attendre(WidgetTester tester, Finder f,
    {Duration limite = const Duration(seconds: 30)}) async {
  final fin = DateTime.now().add(limite);
  while (DateTime.now().isBefore(fin)) {
    await tester.pump(const Duration(milliseconds: 250));
    if (f.evaluate().isNotEmpty) return true;
  }
  return false;
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('render audit — aucun libellé de repli sur l\'écran d\'accueil évaluateur',
      (tester) async {
    app.main();
    await tester.pump(const Duration(seconds: 2));

    // ── connexion eval3 (station 5, examen 2) ────────────────────────────
    expect(await _attendre(tester, find.byType(TextField)), isTrue,
        reason: 'écran de login jamais rendu');
    final champs = find.byType(TextField);
    await tester.enterText(champs.at(0), 'eval@epos.tn');
    await tester.enterText(champs.at(1), 'Eval@1234');
    await tester.pump(const Duration(milliseconds: 300));
    await tester.tap(find.byType(ElevatedButton).first);

    // On attend l'accueil. ⚠️ Si le login meurt en CORS (port ≠ 4200/4300) on
    // reste sur le login : ce n'est PAS un bug applicatif, c'est le harnais.
    final accueil = find.textContaining('Bonjour');
    expect(await _attendre(tester, accueil, limite: const Duration(seconds: 45)), isTrue,
        reason: 'accueil jamais atteint — vérifier --web-port=4300 (CORS passerelle)');

    // ── Attendre une ISSUE, pas une durée ────────────────────────────────
    // Piège vécu le 2026-07-20 : auditer après un délai fixe de 10 s échantillonne
    // l'état de CHARGEMENT (spinner), pas l'état final — le timeout Dio est à 20 s
    // et le dashboard met 31–61 s pendant une panne. Le test passait donc au vert
    // sur un écran encore en train de charger.
    // On course les deux branches et on note laquelle a gagné, pour qu'un échec
    // dise LAQUELLE (recette du README : succès ET impasse attendus ensemble).
    final erreur = find.textContaining('Impossible');
    final sessions = find.textContaining('Station');
    final vide = find.textContaining('Aucune session');
    final fin = DateTime.now().add(const Duration(seconds: 75));
    String issue = 'aucune (timeout du test)';
    while (DateTime.now().isBefore(fin)) {
      await tester.pump(const Duration(milliseconds: 250));
      if (erreur.evaluate().isNotEmpty)   { issue = 'ERREUR de chargement'; break; }
      if (sessions.evaluate().isNotEmpty) { issue = 'sessions rendues';     break; }
      if (vide.evaluate().isNotEmpty)     { issue = 'aucune session (impasse #238)'; break; }
    }
    debugPrint('╣ ISSUE ATTEINTE : $issue');

    // ── GARDE ANTI-SUCCÈS-À-VIDE ─────────────────────────────────────────
    // Sans ceci, le test passe au VERT sur un écran d'erreur : pas de session
    // affichée ⇒ aucun libellé à auditer ⇒ zéro violation. C'est exactement ce
    // qui s'est produit au premier jet (2026-07-20) — Nada a vu « Impossible de
    // charger les sessions » pendant que le test annonçait « All tests passed ».
    // Un audit de rendu doit prouver qu'il avait quelque chose à regarder.
    final textesBruts = _textesRendus(tester);
    final erreurCharg = textesBruts.any((t) =>
        t.toLowerCase().contains('impossible') ||
        t.toLowerCase().contains('erreur') ||
        t.toLowerCase().contains('réessayer'));
    expect(erreurCharg, isFalse,
        reason: 'issue atteinte = "$issue" — le dashboard n\'a pas chargé, donc RIEN à auditer. '
            'Mesuré le 2026-07-20 : pendant une panne exam-service le dashboard met '
            '31–61 s (0,28 s à l\'état sain) alors que le timeout Dio est de 20 s ⇒ '
            'l\'évaluateur ne voit JAMAIS le tableau dégradé, seulement une erreur. '
            'Textes rendus : $textesBruts');
    expect(textesBruts.length, greaterThan(8),
        reason: 'écran quasi vide (${textesBruts.length} textes) — audit non concluant');

    // ── L'AUDIT ──────────────────────────────────────────────────────────
    final textes = textesBruts;
    debugPrint('╔══ RENDER AUDIT — ${textes.length} textes visibles');
    for (final t in textes) {
      debugPrint('║  ${t.length > 100 ? t.substring(0, 100) : t}');
    }
    debugPrint('╚══');

    final violations = <String>[];
    for (final t in textes) {
      _interdits.forEach((re, pourquoi) {
        if (re.hasMatch(t)) violations.add('$pourquoi → "$t"');
      });
    }

    expect(violations, isEmpty,
        reason: 'valeurs de repli rendues à l\'écran :\n  ${violations.join("\n  ")}');
  });
}
