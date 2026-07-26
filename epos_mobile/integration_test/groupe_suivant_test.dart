// integration_test/groupe_suivant_test.dart
//
// #248 + #209 — « GROUPE SUIVANT » PILOTÉ DEPUIS L'UI : garde du bouton, navigation
// par l'ACTE (POST avancer), écran intact, et ANCRE DU MINUTEUR.
//
// #248 (garde) : l'ancienne garde `lot.numero >= lot.total` comparait le numéro du
// GROUPE au nombre de groupes — vraie à l'envers sous le carré latin (bouton grisé au
// premier passage, actif au dernier où le clic vidait l'écran). Le serveur envoie
// désormais `groupeSuivantDisponible` ; on mesure l'état ACTIVÉ/DÉSACTIVÉ du widget.
// (Le cas-piège « groupe 2 au rang 1 » est épinglé par le test unitaire
// drapeauIndependantDuNumeroDeGroupe — sur cette fixture il vit sur la station de
// l'AUTRE évaluateur.)
//
// #209 (ancre) : le compte à rebours s'ancrait sur le créneau PLANIFIÉ, précalculé
// depuis launched_at — vécu par Nada : « 12:51 » restants sur une station de 2 min.
// Il s'ancre désormais sur `debut_reel`, horodaté par le serveur quand l'évaluateur
// OUVRE le groupe : le badge doit ouvrir à ~02:00 pile, et repartir à plein après
// « Groupe suivant » (qui est maintenant un POST : valider ne fait plus avancer).
//
// Fixture : examen 35 « exam test 2 » (construit par Nada), station 62, eval2@epos.tn.
// Lancement :
//   bash integration_test/run_scenario.sh groupe-suivant

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:epos_mobile/main.dart' as app;
import 'package:epos_mobile/features/grading/presentation/widgets/passage_countdown_badge.dart';

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

/// Lit le compte à rebours, SCOPÉ au badge (jamais un scan global MM:SS — piège
/// du 2026-07-20 : l'accueil rend un `heureDebut` statique de la même forme).
/// Secondes restantes ; négatif si « +MM:SS » (dépassement).
int? _lireCompteur(WidgetTester tester) {
  final textes = find.descendant(
    of: find.byType(PassageCountdownBadge),
    matching: find.byType(Text),
  );
  final re = RegExp(r'^\+?(\d{2}):(\d{2})$');
  for (final e in textes.evaluate()) {
    final t = (e.widget as Text).data?.trim();
    if (t == null) continue;
    final m = re.firstMatch(t);
    if (m != null) {
      final s = int.parse(m.group(1)!) * 60 + int.parse(m.group(2)!);
      return t.startsWith('+') ? -s : s;
    }
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

    // ── RANG 1 (exam 35, station 62 : groupe 1/2) — bouton ACTIF, un rang 2 existe.
    // (Le cas-piège « groupe 2 au rang 1 » vit sur la station 63/ev3 ; il reste épinglé
    // par le test unitaire drapeauIndependantDuNumeroDeGroupe.)
    final libelle1 = _libelleGroupe(tester);
    debugPrint('╣ RANG 1 — libellé : $libelle1');
    expect(libelle1, 'Lot 1/2',
        reason: 'fixture inattendue (exam 35, station 62, rang 1 = groupe 1). Lu : $libelle1');

    final actif1 = _boutonActif(tester);
    debugPrint('╣ RANG 1 — « Groupe suivant » actif ? $actif1');
    expect(actif1, isNotNull, reason: 'bouton « Groupe suivant » absent de l\'écran');
    expect(actif1, isTrue,
        reason: 'RÉGRESSION #248 : bouton grisé alors qu\'un rang 2 existe.');

    // ── #209 — L'ANCRE DU MINUTEUR : début RÉEL, plus jamais le créneau planifié.
    // debut_reel était NULL ; le serveur vient de l'horodater à l'ouverture de l'écran.
    // Station de 2 min ⇒ le badge doit ouvrir à ~02:00 (référence vécue par Nada : il
    // affichait « 12:51 » — le planning théorique).
    final sec1 = _lireCompteur(tester);
    debugPrint('╣ RANG 1 — compteur à l\'ouverture : ${sec1}s (attendu ]90..120])');
    expect(sec1, isNotNull, reason: 'badge du minuteur introuvable');
    expect(sec1! <= 120 && sec1 > 90, isTrue,
        reason: 'RÉGRESSION #209 : minuteur non ancré sur le début réel — ${sec1}s '
            'restants sur une station de 2 min (le créneau planifié donnait 12:51).');

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
            (w) => w is Text && (w.data ?? '').trim() == 'Lot 2/2'),
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

    // ── RANG 2 : dernier passage (groupe 2/2) — bouton DÉSACTIVÉ. ───────────
    final actif2 = _boutonActif(tester);
    debugPrint('╣ RANG 2 — « Groupe suivant » actif ? $actif2');
    expect(actif2, isNotNull, reason: 'bouton « Groupe suivant » absent au rang 2');
    expect(actif2, isFalse,
        reason: 'RÉGRESSION #248 : bouton actif au DERNIER passage — c\'est ce clic qui '
            'vidait l\'écran de notation.');

    // ── #209 — l'avance (POST) vient d'ouvrir CE groupe : minuteur reparti à plein.
    final sec2 = _lireCompteur(tester);
    debugPrint('╣ RANG 2 — compteur après l\'avance : ${sec2}s (attendu ]90..120])');
    expect(sec2 != null && sec2 <= 120 && sec2 > 90, isTrue,
        reason: 'RÉGRESSION #209 : le groupe fraîchement ouvert n\'ouvre pas à la durée '
            'pleine (${sec2}s).');

    debugPrint('╣ #248/#209 VÉRIFIÉS : rang aux deux extrémités + ancre du minuteur.');
  });
}
