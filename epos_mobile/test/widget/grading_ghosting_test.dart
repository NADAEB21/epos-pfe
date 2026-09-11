// test/widget/grading_ghosting_test.dart
// ================================================
// #381 — LE PASSAGE AU GROUPE SUIVANT NE MENT PLUS.
//
// Symptôme (constaté par Feten ET Nada) : les champs numériques gardaient le
// TEXTE du groupe précédent après « Groupe suivant » — le bloc, lui, était
// juste (notations: {}, score 0/20). Danger réel : l'évaluateur voit « 5 »
// affiché, croit la note posée, ne la ressaisit pas — et la validation refuse
// une note qui « semble » saisie mais n'existe pas côté données.
//
// Cause : _NumericCell est un StatefulWidget dont le TextEditingController
// naît dans initState(). L'arbre gardant la même forme d'un groupe à l'autre,
// Flutter réutilisait le State — initState jamais rappelé, texte jamais remis
// à zéro. Fix (PR #382) : ValueKey('rotationId_etudiantId_itemId') sur
// _NotationCell — la clé change avec le groupe, l'ancien State est détruit.
//
// Ce test PUMPE le vrai GradingScreen, tape « 5 », fait émettre l'état du
// groupe 2 par le bloc (comme _onGroupeSuivant : rotationId neuf, étudiant
// neuf, notations vides) et affirme ce qui REND : un champ VIDE. Sans la
// ValueKey, ce test est ROUGE (vérifié pendant la relecture de #382).

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/core/offline/offline_bloc.dart';
import 'package:epos_mobile/features/grading/domain/entities/grille.dart';
import 'package:epos_mobile/features/grading/domain/entities/item_evaluation.dart';
import 'package:epos_mobile/features/grading/domain/entities/lot.dart';
import 'package:epos_mobile/features/grading/domain/entities/notation.dart';
import 'package:epos_mobile/features/grading/presentation/bloc/grading_bloc.dart';
import 'package:epos_mobile/features/grading/presentation/screens/grading_screen.dart';
import 'package:epos_mobile/features/home/domain/entities/session.dart';
import 'package:epos_mobile/features/home/presentation/bloc/session_bloc.dart';

/// Bloc factice À FLUX : contrairement au fake figé de
/// student_detail_restore_test, celui-ci sait ÉMETTRE un nouvel état — c'est le
/// cœur du scénario (G1 → G2). (bloc_test reste parké, cf. ce même précédent.)
class _StreamFakeGradingBloc extends Fake implements GradingBloc {
  _StreamFakeGradingBloc(this._state);
  final _controller = StreamController<GradingState>.broadcast();
  GradingState _state;

  @override
  GradingState get state => _state;

  @override
  Stream<GradingState> get stream => _controller.stream;

  void emitState(GradingState s) {
    _state = s;
    _controller.add(s);
  }

  @override
  Future<void> close() async {
    await _controller.close();
  }

  @override
  void add(GradingEvent event) {} // la saisie part au bloc réel ; ici no-op
}

class _FakeSessionBloc extends Fake implements SessionBloc {
  @override
  SessionState get state => SessionInitial();
  @override
  Stream<SessionState> get stream => const Stream.empty();
  @override
  Future<void> close() async {}
  @override
  void add(SessionEvent event) {}
}

class _FakeOfflineBloc extends Fake implements OfflineBloc {
  @override
  OfflineState get state => const OfflineState(isOnline: true);
  @override
  Stream<OfflineState> get stream => const Stream.empty();
  @override
  Future<void> close() async {}
  @override
  void add(OfflineEvent event) {}
}

void main() {
  const item = ItemEvaluation(
    id: 7,
    libelle: 'Calcul de la masse',
    type: TypeCritere.numerique,
    ponderation: 1,
    valeurMax: 10,
    ordre: 1,
  );
  const grille =
      Grille(id: 1, nom: 'Grille test', noteMax: 10, items: [item]);

  const etudiantG1 = Etudiant(
      id: 42, nom: 'Alpha', prenom: 'A', numeroInscription: '21/0042');
  const etudiantG2 = Etudiant(
      id: 43, nom: 'Beta', prenom: 'B', numeroInscription: '21/0043');

  GradingLoaded etatGroupe(int rotationId, Etudiant etudiant) => GradingLoaded(
        rotationId: rotationId,
        stationId: 1,
        grilleId: 1,
        stationNom: 'Station test',
        grille: grille,
        lot: Lot(
            id: rotationId, numero: rotationId, total: 2, etudiants: [etudiant]),
        // exactement ce que _onGroupeSuivant émet : données remises à zéro
        notations: const {},
        etudiantsValides: const {},
      );

  const session = Session(
    id: 1,
    stationNom: 'Station test',
    matiere: null,
    annee: null,
    statut: SessionStatus.enCours,
    heureDebut: '09:00',
    nbEtudiants: 2,
    salle: null,
    lotActuel: 1,
    totalLots: 2,
  );

  testWidgets(
      '#381 — « Groupe suivant » : la cellule numérique rend VIDE, '
      'jamais le texte du groupe précédent', (tester) async {
    final bloc = _StreamFakeGradingBloc(etatGroupe(1, etudiantG1));
    addTearDown(bloc.close);

    await tester.pumpWidget(
      MaterialApp(
        home: MultiBlocProvider(
          providers: [
            BlocProvider<GradingBloc>.value(value: bloc),
            BlocProvider<SessionBloc>.value(value: _FakeSessionBloc()),
            BlocProvider<OfflineBloc>.value(value: _FakeOfflineBloc()),
          ],
          child: const GradingScreen(session: session),
        ),
      ),
    );
    await tester.pump();

    // Groupe 1 : une seule cellule numérique — on y tape « 5 ».
    final champ = find.byType(TextField);
    expect(champ, findsOneWidget,
        reason: 'la grille du groupe 1 doit rendre la cellule numérique');
    await tester.enterText(champ, '5');
    await tester.pump();
    expect(tester.widget<TextField>(champ).controller?.text, '5');

    // « Groupe suivant » : le bloc émet un état NEUF (rotation 2, étudiant B,
    // notations vides) — exactement _onGroupeSuivant.
    bloc.emitState(etatGroupe(2, etudiantG2));
    await tester.pump();

    // CE QUI REND : le champ du groupe 2 est VIDE. Sans la ValueKey de #382,
    // Flutter réutilise l'ancien State et « 5 » reste affiché — l'évaluateur
    // croirait la note déjà posée alors que le backend n'a RIEN.
    final champG2 = find.byType(TextField);
    expect(champG2, findsOneWidget);
    expect(tester.widget<TextField>(champG2).controller?.text, isEmpty,
        reason: 'le texte du groupe précédent ne doit JAMAIS survivre au '
            'passage au groupe suivant (#381)');
  });

  testWidgets(
      '#381 (non-régression « Reprendre ») — une notation EXISTANTE du groupe '
      'courant reste pré-remplie', (tester) async {
    // Un groupe rouvert (Reprendre la notation) doit toujours restaurer les
    // valeurs sauvées — la clé ne doit pas casser ce chemin-là.
    final etat = GradingLoaded(
      rotationId: 1,
      stationId: 1,
      grilleId: 1,
      stationNom: 'Station test',
      grille: grille,
      lot: const Lot(id: 1, numero: 1, total: 2, etudiants: [etudiantG1]),
      notations: const {
        42: {7: Notation(etudiantId: 42, itemId: 7, valeur: 7.5)},
      },
      etudiantsValides: const {},
    );
    final bloc = _StreamFakeGradingBloc(etat);
    addTearDown(bloc.close);

    await tester.pumpWidget(
      MaterialApp(
        home: MultiBlocProvider(
          providers: [
            BlocProvider<GradingBloc>.value(value: bloc),
            BlocProvider<SessionBloc>.value(value: _FakeSessionBloc()),
            BlocProvider<OfflineBloc>.value(value: _FakeOfflineBloc()),
          ],
          child: const GradingScreen(session: session),
        ),
      ),
    );
    await tester.pump();

    final champ = find.byType(TextField);
    expect(champ, findsOneWidget);
    expect(tester.widget<TextField>(champ).controller?.text, '7.5',
        reason: 'les valeurs déjà sauvées doivent pré-remplir la cellule');
  });
}
