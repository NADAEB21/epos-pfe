// integration_test/grading_screen_test.dart
//
// L'ÉCRAN DE NOTATION — jamais piloté jusqu'ici (session 21 l'a écrit noir sur
// blanc : « the grading screen itself was never driven — only the home screen »).
// C'est pourtant le point de convergence des trois affirmations non vérifiées du
// diff : le minuteur (#239), l'audit de rendu sur une surface neuve, et le
// marqueur dégradé « Intitulé indisponible » d'ADR-0015 — qui n'a JAMAIS été vu
// s'afficher, seulement constaté au niveau API.
//
// ── Pourquoi le minuteur se teste ICI et pas seulement en test unitaire ──────
// `_startTimer` décrémente `restant` d'une seconde par tick, LOCALEMENT. Donc
// même avec le `;` parasite de #239 le compte à rebours DESCENDAIT à l'écran :
// il repartait simplement de la durée PLEINE. La signature visible du bug est
// donc la VALEUR D'OUVERTURE, pas le décompte. Un test qui ne vérifie que « ça
// bouge » aurait laissé passer #239 exactement comme la revue de code l'a fait.
// On vérifie les deux : ancrage serveur à l'ouverture ET ticker vivant.
//
// Lancement :
//   bash integration_test/run_scenario.sh grading-nominal
//   bash integration_test/run_scenario.sh grading-outage

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:epos_mobile/main.dart' as app;
import 'package:epos_mobile/features/grading/presentation/widgets/passage_countdown_badge.dart';

/// Motifs de repli qui ne doivent jamais atteindre l'écran (mêmes règles que
/// render_audit_test.dart : la fuite de placeholder est UNE classe de bug).
/// `Station \d+` reste interdit : c'est le repli qu'ADR-0015 supprime.
final _interdits = <RegExp, String>{
  RegExp(r'^Station \d+$'): 'nom de station de repli (ADR-0015 : supprimé côté serveur)',
  RegExp(r'\bnull\b'): 'littéral « null » rendu',
  RegExp(r'\bNaN\b'): 'NaN rendu',
  RegExp(r'N/A'): 'placeholder « N/A »',
  RegExp(r'\b\d+/0\b'): 'compteur X/0 (dénominateur nul)',
};

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

/// Attend un finder SANS pumpAndSettle : l'app a des timers périodiques
/// (rafraîchissement dashboard, compte à rebours 1 s) donc l'arbre n'est JAMAIS
/// quiescent — pumpAndSettle brûlerait tout son timeout.
Future<bool> _attendre(WidgetTester tester, Finder f,
    {Duration limite = const Duration(seconds: 30)}) async {
  final fin = DateTime.now().add(limite);
  while (DateTime.now().isBefore(fin)) {
    await tester.pump(const Duration(milliseconds: 250));
    if (f.evaluate().isNotEmpty) return true;
  }
  return false;
}

/// Le badge du minuteur rend `MM:SS`, préfixé de `+` en dépassement
/// (passage_countdown_badge.dart:96-108 — `.abs()`, volontairement NON borné à
/// zéro : ADR-0014 n'autorise que le PLANCHER).
final _mmss = RegExp(r'^\+?(\d{2}):(\d{2})$');

/// Renvoie le compte à rebours affiché, en secondes ; négatif si dépassement.
/// null si le badge n'est pas rendu.
///
/// ⚠️ La lecture est SCOPÉE au sous-arbre de `PassageCountdownBadge`, jamais à
/// l'ensemble des textes. Piège vécu le 2026-07-20 : l'écran de notation est
/// poussé PAR-DESSUS l'accueil, donc `tester.allWidgets` contient AUSSI les
/// libellés de l'accueil — dont `heureDebut` (« 02:40 »), qui a exactement la
/// forme `MM:SS`. Un scan global lisait ce libellé STATIQUE au lieu du minuteur
/// (« 08:17 ») et rapportait donc « écart 0s » — un faux « ticker mort » très
/// convaincant. Une assertion doit viser LE widget, pas une forme de texte.
int? _lireCompteur(WidgetTester tester) {
  final textes = find.descendant(
    of: find.byType(PassageCountdownBadge),
    matching: find.byType(Text),
  );
  for (final e in textes.evaluate()) {
    final t = (e.widget as Text).data?.trim();
    if (t == null) continue;
    final m = _mmss.firstMatch(t);
    if (m != null) {
      final s = int.parse(m.group(1)!) * 60 + int.parse(m.group(2)!);
      return t.startsWith('+') ? -s : s;
    }
  }
  return null;
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('écran de notation — ancrage minuteur, ticker, audit de rendu',
      (tester) async {
    app.main();
    await tester.pump(const Duration(seconds: 2));

    // ── connexion eval3 (station 5, examen 2) ────────────────────────────
    // ⚠️ La session est PERSISTÉE (token en storage) : si un run précédent
    // s'est connecté dans le même profil Chrome, l'app restaure la session via
    // `GET /auth/me` et va DIRECTEMENT à l'accueil — l'écran de login n'est
    // jamais rendu. Exiger le login faisait échouer le test sur un état
    // parfaitement sain (vécu le 2026-07-20). On course les deux entrées.
    final champLogin = find.byType(TextField);
    final accueil    = find.textContaining('Bonjour');
    final finAuth = DateTime.now().add(const Duration(seconds: 45));
    String entree = 'aucune (timeout)';
    while (DateTime.now().isBefore(finAuth)) {
      await tester.pump(const Duration(milliseconds: 250));
      if (accueil.evaluate().isNotEmpty)    { entree = 'session déjà active'; break; }
      if (champLogin.evaluate().isNotEmpty) { entree = 'écran de login';      break; }
    }
    debugPrint('╣ ENTRÉE : $entree');

    if (entree == 'écran de login') {
      await tester.enterText(champLogin.at(0), 'eval@epos.tn');
      await tester.enterText(champLogin.at(1), 'Eval@1234');
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.byType(ElevatedButton).first);

      // ⚠️ Si le login meurt en CORS (port ≠ 4200/4300) on reste sur le login :
      // harnais, pas bug applicatif.
      expect(
          await _attendre(tester, accueil, limite: const Duration(seconds: 45)),
          isTrue,
          reason: 'accueil jamais atteint — vérifier --web-port=4300 (CORS passerelle)');
    }

    expect(entree, isNot('aucune (timeout)'),
        reason: 'ni login ni accueil rendus en 45 s — l\'app n\'a pas démarré. '
            'Textes : ${_textesRendus(tester)}');

    // ── Attendre une ISSUE du dashboard, pas une durée ───────────────────
    // Piège du 2026-07-20 : auditer après un délai fixe échantillonne le
    // spinner. On course les trois issues et on nomme celle atteinte.
    // ⚠️ NE PAS utiliser find.widgetWithText(ElevatedButton, ...) : `find.byType`
    // matche le type EXACT, or `ElevatedButton.icon` construit un sous-type privé
    // (`_ElevatedButtonWithIcon`). Le bouton était bien à l'écran et le finder
    // ne le voyait pas — faux négatif vécu le 2026-07-20.
    final btnNotation = find.text('Reprendre la notation');
    final erreur = find.textContaining('Impossible');
    final vide   = find.textContaining('Aucune session');
    final fin = DateTime.now().add(const Duration(seconds: 75));
    String issue = 'aucune (timeout du test)';
    while (DateTime.now().isBefore(fin)) {
      await tester.pump(const Duration(milliseconds: 250));
      if (btnNotation.evaluate().isNotEmpty) { issue = 'session notable'; break; }
      if (erreur.evaluate().isNotEmpty)      { issue = 'ERREUR de chargement'; break; }
      if (vide.evaluate().isNotEmpty)        { issue = 'aucune session (impasse #238)'; break; }
    }
    debugPrint('╣ ISSUE ACCUEIL : $issue');

    // GARDE ANTI-SUCCÈS-À-VIDE nº1 — sans session notable il n'y a pas d'écran
    // de notation à atteindre, et tout ce qui suit passerait « au vert » à vide.
    expect(issue, 'session notable',
        reason: 'issue accueil = "$issue" — impossible d\'atteindre la notation. '
            'Textes rendus : ${_textesRendus(tester)}');

    // ── entrée dans l'écran de notation ──────────────────────────────────
    await tester.tap(btnNotation);
    await tester.pump(const Duration(milliseconds: 500));

    // On attend une preuve d'écran de notation : le bandeau « N étudiants »
    // (grading_screen.dart:1131) n'existe que là.
    final marqueurNotation =
        find.byWidgetPredicate((w) => w is Text && (w.data ?? '').contains('étudiants'));
    final okNotation = await _attendre(tester, marqueurNotation,
        limite: const Duration(seconds: 45));

    // GARDE ANTI-SUCCÈS-À-VIDE nº2 — l'écran de notation est-il VRAIMENT là ?
    expect(okNotation, isTrue,
        reason: 'écran de notation jamais rendu après le tap. '
            'Textes rendus : ${_textesRendus(tester)}');

    final textesNotation = _textesRendus(tester);
    debugPrint('╔══ ÉCRAN DE NOTATION — ${textesNotation.length} textes visibles');
    for (final t in textesNotation) {
      debugPrint('║  ${t.length > 100 ? t.substring(0, 100) : t}');
    }
    debugPrint('╚══');

    // ── 1. ANCRAGE DU MINUTEUR (la vraie signature de #239) ──────────────
    final ouverture = _lireCompteur(tester);
    expect(ouverture, isNotNull,
        reason: 'badge de compte à rebours absent de l\'écran de notation — '
            'rien à vérifier. Textes : $textesNotation');
    debugPrint('╣ MINUTEUR à l\'ouverture : ${ouverture}s');

    // Borne LÂCHE et non dérivée d'un `now` local (règle nº2 du README) : on
    // n'affirme pas « il doit rester 9:00 », on affirme que la valeur d'ouverture
    // est ANCRÉE SUR LE SERVEUR, donc strictement inférieure à la durée pleine
    // de la station. Avec le `;` de #239 elle valait exactement la durée pleine.
    // C'est un PLANCHER vérifié, jamais un plafond : le dépassement (< 0) reste
    // légitime et n'est pas traité comme un échec.
    expect(ouverture! < 15 * 60, isTrue,
        reason: 'le minuteur ouvre à ${ouverture}s = la durée PLEINE de la station : '
            'le compte à rebours ignore debutCreneau — régression de #239 '
            '(le `;` parasite de grading_bloc.dart:671).');

    // ── 2. LE TICKER EST VIVANT ──────────────────────────────────────────
    // Nécessaire mais PAS suffisant (cf. en-tête) : sur develop il l'était déjà.
    //
    // ⚠️ PIÈGE (vécu le 2026-07-20, faux positif « ticker mort ») :
    // `tester.pump(Duration)` PLANIFIE une frame, il n'avance PAS l'horloge
    // réelle — contrairement à FakeAsync en test unitaire. Or le `Timer.periodic`
    // du bloc (grading_bloc.dart:653) se déclenche sur le temps MUR. Une boucle
    // de N pumps s'exécute en quelques millisecondes : le minuteur n'a alors
    // aucune raison d'avoir tiqué, et le test conclut « écart 0s » à tort.
    // On attend donc sur `DateTime.now()`, en pompant pour rafraîchir l'arbre.
    final debutAttente = DateTime.now();
    final cible = debutAttente.add(const Duration(seconds: 4));
    while (DateTime.now().isBefore(cible)) {
      await tester.pump(const Duration(milliseconds: 100));
    }
    final ecouleReel = DateTime.now().difference(debutAttente);
    final apres = _lireCompteur(tester);
    debugPrint('╣ MINUTEUR après ${ecouleReel.inMilliseconds} ms réels : ${apres}s '
        '(écart ${ouverture - apres!}s)');

    // Garde anti-vacuité : si le temps réel n'a pas passé, l'assertion suivante
    // ne prouve rien — on le dit plutôt que de laisser un vert muet.
    expect(ecouleReel.inSeconds, greaterThanOrEqualTo(3),
        reason: 'seulement ${ecouleReel.inMilliseconds} ms réels écoulés : '
            'le test n\'a pas laissé le temps au minuteur de tiquer.');

    expect(apres < ouverture, isTrue,
        reason: 'le minuteur ne décroît pas ($ouverture → $apres) après '
            '${ecouleReel.inMilliseconds} ms réels : ticker mort.');

    // ── 3. AUDIT DE RENDU sur une surface JAMAIS auditée ─────────────────
    // C'est ici qu'on verrait « Station 5 » réapparaître, ou une fuite de null.
    final violations = <String>[];
    for (final t in textesNotation) {
      _interdits.forEach((re, pourquoi) {
        if (re.hasMatch(t)) violations.add('$pourquoi → "$t"');
      });
    }
    expect(violations, isEmpty,
        reason: 'valeurs de repli rendues sur l\'écran de notation :\n  '
            '${violations.join("\n  ")}');

    // ── 4. RAPPORT : quel intitulé de station l'évaluateur voit-il ? ──────
    // Pas d'assertion : le scénario nominal doit montrer le vrai intitulé,
    // le scénario de panne « Intitulé indisponible ». On IMPRIME lequel, pour
    // qu'un vert ne puisse pas être muet (« un audit vert qui ne dit pas ce
    // qu'il a vu n'est pas une preuve »).
    final degrade = textesNotation.any((t) => t.contains('Intitulé indisponible'));
    debugPrint('╣ INTITULÉ DE STATION : ${degrade ? "DÉGRADÉ (Intitulé indisponible)" : "réel"}');
  });
}
