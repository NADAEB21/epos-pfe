// integration_test/timer_anchor_test.dart
//
// Finding #1 (session 19) — ancrage du compte à rebours, VERSION LIVE.
//
// Pourquoi cette version en plus du test unitaire `test/widget/timer_anchor_test.dart` :
//   • le test unitaire utilise un faux repository → il épingle le point-virgule
//     parasite (grading_bloc.dart:671) de façon déterministe, mais il ne peut
//     RIEN dire de ce que le serveur envoie réellement (`debutPrevu`,
//     `dureeStationMin` peuplés bout en bout ?).
//   • `flutter drive` recompile les sources à chaud : AUCUN cache navigateur,
//     AUCUN service worker. Ce test tranche donc aussi l'observation `+53:30`
//     de la session 19, que la source actuelle ne sait pas produire.
//
// Fixture posée par run_scenario.sh (scénario `timer-anchor`) :
//   rotation 141 → debut_creneau = maintenant − 6 min  (dans la fenêtre de 45
//   min, donc la session reste joignable), station 5, durée 15 min.
// ⇒ il doit rester ~9 min. Le défaut actuel affiche 15:00.
//
// Doctrine (README §3) : assertion de PLANCHER (l'évaluateur voit le temps qui
// lui reste), jamais de PLAFOND. On ne retire aucune session expirée.
//
// ⚠️ Ce test DOIT échouer sur develop ed13a33. C'est son rôle (README §4).

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:epos_mobile/main.dart' as app;
import 'package:epos_mobile/features/grading/presentation/widgets/passage_countdown_badge.dart';

const _email = 'eval@epos.tn';
const _motDePasse = 'Eval@1234';

/// Durée de station de la fixture (rotation 141 / station 5).
const _dureeStation = Duration(minutes: 15);

/// Temps écoulé imposé par la fixture.
const _ecoule = Duration(minutes: 6);

/// Tous les textes actuellement montés — indispensable pour diagnostiquer un
/// échec sur pile réelle : sans ça, un `findsOneWidget` raté ne dit pas si on
/// est resté au login, tombé sur une impasse, ou sur un écran d'erreur.
String _ecranVisible(WidgetTester tester) {
  final textes = tester
      .widgetList<Text>(find.byType(Text))
      .map((t) => t.data)
      .where((d) => d != null && d.trim().isNotEmpty)
      .take(40)
      .join(' | ');
  return textes.isEmpty ? '(aucun Text monté)' : textes;
}

/// Pompe jusqu'à ce que l'un des finders apparaisse, puis renvoie son index.
/// Renvoie -1 en cas d'expiration.
///
/// ⚠️ NE PAS remplacer par `pumpAndSettle`. L'app porte des timers périodiques
/// (rafraîchissement du dashboard, compte à rebours) : l'arbre n'atteint donc
/// JAMAIS l'état quiescent qu'attend pumpAndSettle, qui consomme alors tout son
/// délai avant d'abandonner. C'est ce qui faisait durer ce test 11 minutes.
Future<int> _attendreUnDe(
  WidgetTester tester,
  List<Finder> finders, {
  Duration limite = const Duration(seconds: 30),
}) async {
  final fin = DateTime.now().add(limite);
  while (DateTime.now().isBefore(fin)) {
    await tester.pump(const Duration(milliseconds: 250));
    for (var i = 0; i < finders.length; i++) {
      if (finders[i].evaluate().isNotEmpty) return i;
    }
  }
  return -1;
}

Future<void> _connecter(WidgetTester tester) async {
  // L'app peut déjà porter un token persisté : on ne se connecte que si les
  // champs sont là.
  final champs = find.byType(TextFormField);
  if (champs.evaluate().isEmpty) {
    debugPrint('[timer-anchor] déjà authentifié — login sauté');
    return;
  }

  await tester.enterText(champs.at(0), _email);
  await tester.enterText(champs.at(1), _motDePasse);
  await tester.pump(const Duration(milliseconds: 500));

  await tester.tap(find.byType(ElevatedButton).first);

  // Appel réseau réel vers la passerelle :8080.
  // ⚠️ Le port DOIT être 4300 (voir run_scenario.sh) : sur un port aléatoire,
  // la passerelle refuse en CORS et on reste bloqué ici.
  final vu = await _attendreUnDe(tester, [find.textContaining('Bonjour')]);
  debugPrint('[timer-anchor] après login (${vu >= 0 ? "accueil atteint" : "TIMEOUT"}) '
      '= ${_ecranVisible(tester)}');
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets(
    'finding #1 — le badge de passage défalque le temps déjà écoulé',
    (tester) async {
      app.main();
      await _attendreUnDe(tester, [find.byType(TextFormField)]);

      await _connecter(tester);

      // ── La session doit être joignable ────────────────────────────────────
      // Si ceci échoue, on est dans une IMPASSE (#238) et non dans le bug du
      // minuteur : la fixture n'a pas rendu la rotation EN_COURS. À distinguer
      // absolument — cf. les 3 impasses A/B/C du catalogue S2.
      final acces = find.text('Reprendre la notation');
      final impasse = find.text('Aucune session en cours');

      // On attend explicitement l'UNE OU L'AUTRE branche : c'est la seule façon
      // de distinguer « le minuteur est faux » de « on n'atteint même pas la
      // notation » (#238). L'API dit `EN_COURS` pour la rotation 141 avec cette
      // fixture — vérifié au curl la même session — donc l'impasse ici serait un
      // désaccord MOBILE/BACKEND, pas la dérive horaire du backend.
      final branche = await _attendreUnDe(tester, [acces, impasse]);
      debugPrint('[timer-anchor] écran d\'accueil = ${_ecranVisible(tester)}');

      expect(
        branche,
        isNot(1),
        reason: 'IMPASSE #238 sur une rotation que le backend renvoie EN_COURS. '
            'Ce n\'est PAS le bug du minuteur : le mobile n\'offre aucun accès à '
            'la notation alors que /dashboard annonce statut=EN_COURS pour la '
            'rotation 141. À filer séparément.',
      );
      expect(
        branche,
        0,
        reason: 'PRÉ-REQUIS NON TENU (pas le bug testé) : ni carte de notation '
            'ni écran d\'impasse après 30 s. Vérifier que la fixture '
            'timer-anchor est posée et que le port est bien 4300 (CORS).',
      );

      await tester.tap(acces);
      await _attendreUnDe(tester, [find.byType(PassageCountdownBadge)]);

      // ── Lecture du badge ──────────────────────────────────────────────────
      // On lit la PROPRIÉTÉ `tempsRestant` du widget plutôt que son texte :
      // le texte est formaté en valeur absolue (`aff = tempsRestant.abs()`),
      // donc « 06:00 » peut signifier +6 ou −6. La propriété, elle, est signée.
      final badge = find.byType(PassageCountdownBadge);
      expect(badge, findsOneWidget,
          reason: 'le badge de compte à rebours doit être monté dans l\'AppBar');

      final restant =
          tester.widget<PassageCountdownBadge>(badge).tempsRestant;

      // ── Finding #7 (Nada, 2026-07-20) — nom de station affiché ────────────
      // ⚠️ ORDRE VOLONTAIRE : cette vérification est placée AVANT les assertions
      // du minuteur. Placée après, elle ne s'exécutait JAMAIS — la première
      // assertion du minuteur échoue (défaut connu) et interrompt le test. Une
      // vérification située derrière une assertion qui échoue n'existe pas.
      //
      // Leçon de méthode : la version précédente ne lisait QUE la propriété
      // `tempsRestant`. Elle ne pouvait donc pas voir un nom de station faux —
      // on ne trouve que ce qu'on interroge. On lit maintenant ce que l'écran
      // AFFICHE.
      //
      // Seul producteur de « Station <id> » : le repli serveur
      // (`ExamServiceClient:44 STATION_FALLBACK_PREFIX`), déclenché sur
      // exam-service injoignable / corps sans `data.nom`. Le mobile ne fabrique
      // jamais cette chaîne (aucun `'Station '` dans `lib/`). Repli SILENCIEUX
      // (`log.warn` seul).
      final placeholder = RegExp(r'^\s*Station\s+\d+\s*$');
      final titres = tester
          .widgetList<Text>(find.byType(Text))
          .map((t) => t.data)
          .whereType<String>()
          .where((s) => s.toLowerCase().contains('station'))
          .toList();
      debugPrint('[timer-anchor] NOMS STATION à l\'écran = $titres');

      expect(
        titres.where(placeholder.hasMatch),
        isEmpty,
        reason: 'Libellé de repli « Station <id> » au lieu du vrai nom. Produit '
            'par ExamServiceClient:44 quand l\'appel exam-service échoue, et '
            'avalé silencieusement. PR #234 (« fix timer and station name ») '
            'prétend corriger ceci ; textes relevés : $titres',
      );

      expect(restant, isNotNull,
          reason: 'une session en cours doit porter un temps restant');

      // Assertion discriminante — cf. l'en-tête du test unitaire : la forme
      // « elapsed < duree » passerait AU VERT sur le défaut actuel.
      expect(
        restant!.inSeconds,
        lessThan((_dureeStation - const Duration(minutes: 3)).inSeconds),
        reason: 'ÉCHEC ATTENDU sur develop ed13a33 : grading_bloc.dart:671 '
            'renvoie _dureeStation sans soustraire elapsed (point-virgule '
            'parasite) → 15:00 au lieu de ~9:00.',
      );

      // Garde-fou anti sur-correction (double soustraction / mauvais ancrage).
      expect(
        restant.inSeconds,
        greaterThan((_dureeStation - _ecoule - const Duration(minutes: 3)).inSeconds),
        reason: 'temps restant trop faible : ancrage sur une rotation ANTÉRIEURE '
            '(c\'est exactement la forme du symptôme +53:30 de la session 19) '
            'ou double soustraction.',
      );

      // Trace lisible dans la sortie du run, pour le cas où l'on cherche à
      // caractériser +53:30 : on veut la valeur brute, pas seulement le verdict.
      debugPrint('[timer-anchor] tempsRestant = ${restant.inSeconds}s '
          '(attendu ~${(_dureeStation - _ecoule).inSeconds}s)');

    },
  );
}
