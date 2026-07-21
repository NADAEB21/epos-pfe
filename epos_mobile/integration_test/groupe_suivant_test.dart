// integration_test/groupe_suivant_test.dart
//
// #248 — LA GARDE DU BOUTON « GROUPE SUIVANT », PILOTÉE DEPUIS L'UI.
//
// Ce que le test doit prouver, et pourquoi il faut l'UI pour le prouver :
// l'ancienne garde était `lot.numero >= lot.total`, c'est-à-dire le numéro du
// GROUPE courant comparé au nombre de groupes. Le carré latin faisant tourner
// les groupes, la station 59 reçoit le GROUPE 2 au RANG 1 : la condition était
// donc vraie exactement à l'envers —
//   • rang 1 (groupe 2/2) → bouton GRISÉ alors qu'un passage restait ;
//   • rang 2 (groupe 1/2) → bouton ACTIF alors qu'il n'y avait plus rien, et le
//     clic remplaçait tout l'écran par une erreur (le builder de l'écran ne rend
//     que GradingLoaded, donc GradingError laissait un spinner définitif).
//
// Une assertion d'API ne pouvait pas attraper ça : le drapeau serveur peut être
// juste pendant que le bouton reste faux. C'est l'état ACTIVÉ/DÉSACTIVÉ du
// widget qui est le défaut, donc c'est lui qu'on mesure.
//
// Fixture : examen 33, vague 2, station 59, eval2@epos.tn.
// Lancement :
//   bash integration_test/run_scenario.sh groupe-suivant

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

/// Attend un finder SANS pumpAndSettle : l'app a des timers périodiques, donc
/// l'arbre n'est jamais quiescent et pumpAndSettle brûlerait tout son timeout.
Future<bool> _attendre(WidgetTester tester, Finder f,
    {Duration limite = const Duration(seconds: 30)}) async {
  final fin = DateTime.now().add(limite);
  while (DateTime.now().isBefore(fin)) {
    await tester.pump(const Duration(milliseconds: 250));
    if (f.evaluate().isNotEmpty) return true;
  }
  return false;
}

/// Le bouton « Groupe suivant », retrouvé par son LIBELLÉ puis remonté jusqu'au
/// bouton qui le contient.
///
/// ⚠️ NE PAS utiliser `find.byType(ElevatedButton)` : il matche le type EXACT,
/// or `ElevatedButton.icon` construit `_ElevatedButtonWithIcon`, un sous-type
/// privé. Le bouton est à l'écran et le finder ne le voit pas (faux négatif
/// vécu le 2026-07-20). `w is ElevatedButton` traverse la hiérarchie, lui.
Finder _boutonGroupeSuivant() => find.ancestor(
      of: find.text('Groupe suivant'),
      matching: find.byWidgetPredicate((w) => w is ElevatedButton),
    );

/// null si le bouton n'est pas rendu du tout — à distinguer d'un bouton présent
/// mais désactivé, sans quoi « absent » passerait pour « grisé » et le test
/// serait vert sur un écran vide.
bool? _boutonActif(WidgetTester tester) {
  final f = _boutonGroupeSuivant();
  if (f.evaluate().isEmpty) return null;
  return (tester.widget(f.first) as ElevatedButton).onPressed != null;
}

/// Le bandeau d'en-tête « Lot N/M » (qui nomme en réalité un GROUPE — #253).
/// Sert ici de témoin du passage courant : 2/2 au rang 1, 1/2 au rang 2.
String? _libelleGroupe(WidgetTester tester) {
  for (final t in _textesRendus(tester)) {
    if (RegExp(r'^Lot \d+/\d+$').hasMatch(t)) return t;
  }
  return null;
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('#248 — le bouton suit le RANG, et la fin de vague ne casse pas l\'écran',
      (tester) async {
    app.main();
    await tester.pump(const Duration(seconds: 2));

    // ── authentification (session possiblement déjà restaurée) ──────────────
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
      await tester.enterText(champLogin.at(0), 'eval2@epos.tn');
      await tester.enterText(champLogin.at(1), 'Eval@1234');
      await tester.pump(const Duration(milliseconds: 300));
      await tester.tap(find.byType(ElevatedButton).first);
      expect(
          await _attendre(tester, accueil, limite: const Duration(seconds: 45)),
          isTrue,
          reason: 'accueil jamais atteint — vérifier --web-port=4300 (CORS passerelle)');
    }
    expect(entree, isNot('aucune (timeout)'),
        reason: 'ni login ni accueil rendus en 45 s. Textes : ${_textesRendus(tester)}');

    // ── on court les trois issues du dashboard, jamais un délai fixe ────────
    final btnNotation = find.text('Reprendre la notation');
    final erreur = find.textContaining('Impossible');
    final vide   = find.textContaining('Aucune session');
    final fin = DateTime.now().add(const Duration(seconds: 75));
    String issue = 'aucune (timeout du test)';
    while (DateTime.now().isBefore(fin)) {
      await tester.pump(const Duration(milliseconds: 250));
      if (btnNotation.evaluate().isNotEmpty) { issue = 'session notable'; break; }
      if (erreur.evaluate().isNotEmpty)      { issue = 'ERREUR de chargement'; break; }
      if (vide.evaluate().isNotEmpty)        { issue = 'aucune session'; break; }
    }
    debugPrint('╣ ISSUE ACCUEIL : $issue');
    expect(issue, 'session notable',
        reason: 'issue accueil = "$issue" — la notation est inatteignable. '
            'Textes : ${_textesRendus(tester)}');

    // GARDE ANTI-SUCCÈS-À-VIDE : le scénario met au repos la session fantôme
    // (#249) précisément pour qu'il n'y ait qu'UNE carte. Si la mise au repos
    // n'a pas pris, on entrerait dans la mauvaise session et tout le reste
    // serait mesuré sur le mauvais examen — donc on le vérifie au lieu de
    // l'espérer.
    expect(btnNotation.evaluate().length, 1,
        reason: 'attendu 1 session en cours, trouvé ${btnNotation.evaluate().length} — '
            'la rotation fantôme 191 (#249) n\'a pas été mise au repos.');

    await tester.tap(btnNotation);
    await tester.pump(const Duration(milliseconds: 500));

    final marqueurNotation =
        find.byWidgetPredicate((w) => w is Text && (w.data ?? '').contains('étudiants'));
    expect(await _attendre(tester, marqueurNotation, limite: const Duration(seconds: 45)),
        isTrue,
        reason: 'écran de notation jamais rendu. Textes : ${_textesRendus(tester)}');

    // ── RANG 1 : le piège. Groupe 2/2, et pourtant un passage RESTE. ────────
    final libelle1 = _libelleGroupe(tester);
    debugPrint('╣ RANG 1 — libellé : $libelle1');
    expect(libelle1, 'Lot 2/2',
        reason: 'fixture inattendue : la station 59 doit recevoir le GROUPE 2 au rang 1 '
            '(c\'est tout l\'intérêt du cas). Lu : $libelle1');

    final actif1 = _boutonActif(tester);
    debugPrint('╣ RANG 1 — « Groupe suivant » actif ? $actif1  '
        '(ancienne garde numero>=total ⇒ aurait grisé)');
    expect(actif1, isNotNull, reason: 'bouton « Groupe suivant » absent de l\'écran');
    expect(actif1, isTrue,
        reason: 'RÉGRESSION #248 : bouton grisé au PREMIER passage alors qu\'un rang 2 '
            'existe. C\'est le symptôme exact rapporté le 2026-07-21.');

    // ── le clic doit NAVIGUER, pas détruire l'écran ─────────────────────────
    await tester.tap(_boutonGroupeSuivant());
    await tester.pump(const Duration(milliseconds: 500));

    // Le bouton ouvre d'abord une confirmation quand tous les étudiants ne sont
    // pas notés (grading_screen.dart, _confirmerGroupeSuivant) — et ici AUCUN ne
    // l'est, puisque ce scénario teste la navigation, pas la saisie. Sans ce
    // « Continuer », le test restait sur la boîte de dialogue et concluait « la
    // navigation n'a pas eu lieu » : un faux échec, sur une garde applicative
    // parfaitement saine.
    final continuer = find.text('Continuer');
    if (await _attendre(tester, continuer, limite: const Duration(seconds: 5))) {
      debugPrint('╣ confirmation « étudiants non validés » → Continuer');
      await tester.tap(continuer);
      await tester.pump(const Duration(milliseconds: 500));
    }

    final passeAuRang2 = await _attendre(
        tester,
        find.byWidgetPredicate(
            (w) => w is Text && (w.data ?? '').trim() == 'Lot 1/2'),
        limite: const Duration(seconds: 30));

    debugPrint('╣ APRÈS CLIC — libellé : ${_libelleGroupe(tester)}');

    // L'écran de notation doit TOUJOURS être là. C'était le cœur du défaut :
    // GradingError remplaçait la page par un spinner dont seul un refresh
    // sortait — en renvoyant à l'accueil, notes saisies hors de vue.
    expect(marqueurNotation.evaluate(), isNotEmpty,
        reason: 'l\'écran de notation a disparu après le clic — c\'est le défaut #248. '
            'Textes : ${_textesRendus(tester)}');
    expect(find.textContaining('Aucun groupe suivant').evaluate(), isEmpty,
        reason: 'erreur « Aucun groupe suivant » affichée alors qu\'un passage existait');
    expect(passeAuRang2, isTrue,
        reason: 'le groupe affiché n\'a pas changé — la navigation par RANG n\'a pas eu lieu. '
            'Textes : ${_textesRendus(tester)}');

    // ── RANG 2 : dernier passage. Groupe 1/2 — l'ancienne garde l'aurait ACTIVÉ.
    final actif2 = _boutonActif(tester);
    debugPrint('╣ RANG 2 — « Groupe suivant » actif ? $actif2  '
        '(ancienne garde numero(1)>=total(2) faux ⇒ aurait activé ⇒ écran vidé)');
    expect(actif2, isNotNull, reason: 'bouton « Groupe suivant » absent au rang 2');
    expect(actif2, isFalse,
        reason: 'RÉGRESSION #248 : bouton actif au DERNIER passage — c\'est ce clic qui '
            'vidait l\'écran de notation.');

    debugPrint('╣ #248 VÉRIFIÉ : garde alignée sur le RANG aux DEUX extrémités.');
  });
}
