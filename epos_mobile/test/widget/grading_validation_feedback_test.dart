// test/widget/grading_validation_feedback_test.dart
// ================================================
// #417 (recette du 05/09) — APRÈS « VALIDER GROUPE », L'ÉCRAN LE DIT.
//
// Constat de Nada : elle validait un groupe et rien ne changeait — pas de
// message, le bouton restait actif, elle ne savait pas si elle pouvait passer
// au groupe suivant. Le bloc écrivait `messageSucces: 'Groupe validé !'`
// (grading_bloc.dart) et AUCUN widget ne le lisait ; `lotValide` n'était lu
// que par les gardes du bloc.
//
// Contrat épinglé (harnais fake-bloc à flux de grading_error_dialog_test) :
//   1. messageSucces émis → SnackBar avec le texte ;
//   2. lotValide + groupe suivant disponible → bandeau « Groupe N validé — vous
//      pouvez passer au groupe suivant » ;
//   3. « Valider groupe » est DÉSACTIVÉ une fois le groupe validé (plus de
//      second POST) ; « Groupe suivant » reste actif ;
//   4. au dernier passage (pas de suivant), pas de bandeau vert : c'est la
//      bannière « Vague terminée » qui parle.

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

class _StreamFakeGradingBloc extends Fake implements GradingBloc {
  _StreamFakeGradingBloc(this._state);
  final _controller = StreamController<GradingState>.broadcast();
  GradingState _state;
  final List<GradingEvent> events = [];

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
  void add(GradingEvent event) => events.add(event);
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

const _item = ItemEvaluation(
  id: 7,
  libelle: 'Calcul de la masse',
  type: TypeCritere.numerique,
  ponderation: 1,
  valeurMax: 10,
  ordre: 1,
);
const _grille = Grille(id: 1, nom: 'Grille test', noteMax: 10, items: [_item]);
const _etudiant =
    Etudiant(id: 42, nom: 'Alpha', prenom: 'A', numeroInscription: '21/0042');

GradingLoaded _etat({
  bool lotValide = false,
  bool groupeSuivantDisponible = true,
  bool vagueTerminee = false,
  String? messageSucces,
}) =>
    GradingLoaded(
      rotationId: 1,
      stationId: 1,
      grilleId: 1,
      stationNom: 'Station test',
      grille: _grille,
      lot: Lot(
        id: 1,
        numero: 2,
        total: 3,
        etudiants: const [_etudiant],
        valide: lotValide,
        groupeSuivantDisponible: groupeSuivantDisponible,
      ),
      notations: const {},
      etudiantsValides: lotValide ? const {42} : const {},
      lotValide: lotValide,
      vagueTerminee: vagueTerminee,
      messageSucces: messageSucces,
    );

const _session = Session(
  id: 1,
  stationNom: 'Station test',
  matiere: null,
  annee: null,
  statut: SessionStatus.enCours,
  heureDebut: '09:00',
  nbEtudiants: 1,
  salle: null,
  lotActuel: 1,
  totalLots: 3,
);

Future<_StreamFakeGradingBloc> _pump(WidgetTester tester, GradingLoaded initial) async {
  final bloc = _StreamFakeGradingBloc(initial);
  addTearDown(bloc.close);
  await tester.pumpWidget(
    MaterialApp(
      home: MultiBlocProvider(
        providers: [
          BlocProvider<GradingBloc>.value(value: bloc),
          BlocProvider<SessionBloc>.value(value: _FakeSessionBloc()),
          BlocProvider<OfflineBloc>.value(value: _FakeOfflineBloc()),
        ],
        child: const GradingScreen(session: _session),
      ),
    ),
  );
  await tester.pump();
  return bloc;
}

ButtonStyleButton _bouton(WidgetTester tester, String label) {
  final finder = find.ancestor(
    of: find.text(label),
    matching: find.byWidgetPredicate((w) => w is ButtonStyleButton),
  );
  return tester.widget<ButtonStyleButton>(finder.first);
}

void main() {
  testWidgets('#417 — messageSucces → SnackBar visible avec son texte',
      (tester) async {
    final bloc = await _pump(tester, _etat());

    bloc.emitState(_etat(lotValide: true, messageSucces: 'Groupe validé !'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.byType(SnackBar), findsOneWidget,
        reason: 'le succès doit être DIT, pas seulement écrit dans le bloc');
    expect(find.text('Groupe validé !'), findsOneWidget);
  });

  testWidgets(
      '#417 — groupe validé + suivant disponible → bandeau vert, « Valider groupe » '
      'désactivé, « Groupe suivant » actif', (tester) async {
    await _pump(tester, _etat(lotValide: true));
    await tester.pump();

    expect(find.byKey(const Key('bandeau-groupe-valide')), findsOneWidget);
    expect(
        find.text('Groupe 2 validé — vous pouvez passer au groupe suivant.'),
        findsOneWidget);

    expect(_bouton(tester, 'Valider groupe').onPressed, isNull,
        reason: 'un groupe validé ne se revalide pas (second POST inutile)');
    expect(_bouton(tester, 'Groupe suivant').onPressed, isNotNull,
        reason: 'l\'action suivante désignée doit être disponible');
  });

  testWidgets(
      '#417 — avant validation : pas de bandeau, « Valider groupe » actif',
      (tester) async {
    await _pump(tester, _etat());
    await tester.pump();

    expect(find.byKey(const Key('bandeau-groupe-valide')), findsNothing);
    expect(_bouton(tester, 'Valider groupe').onPressed, isNotNull);
  });

  testWidgets(
      '#417 — dernier passage validé : la bannière « Vague terminée » parle, pas le bandeau vert',
      (tester) async {
    await _pump(
        tester,
        _etat(
          lotValide: true,
          groupeSuivantDisponible: false,
          vagueTerminee: true,
        ));
    await tester.pump();

    expect(find.byKey(const Key('bandeau-groupe-valide')), findsNothing);
    expect(find.textContaining('Vague terminée pour cette station'), findsOneWidget);
  });
}
