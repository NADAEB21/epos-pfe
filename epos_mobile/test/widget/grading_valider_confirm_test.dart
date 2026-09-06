// test/widget/grading_valider_confirm_test.dart
// ================================================
// #423 (recette du 06/09) — « VALIDER GROUPE » NE PORTE AUCUN AVERTISSEMENT
// DE TEMPS.
//
// L'acte irréversible est le VERROUILLAGE de chaque étudiant, et il a déjà sa
// confirmation dans la fiche (« Verrouiller les notes ? »). Valider un groupe
// dont tous les étudiants sont verrouillés ne fige rien de plus : une boîte de
// temps ici (essayée un instant) arrivait toujours après coup — retirée sur
// décision de Nada. ADR-0014 : l'horloge est un plancher, jamais une garde.
//
// Contrat :
//   1. tout verrouillé, il reste du temps → AUCUNE boîte, la validation part ;
//   2. tout verrouillé, temps écoulé → idem ;
//   3. groupe incomplet → « Groupe incomplet » (contrat #417 inchangé).

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

class _FakeGradingBloc extends Fake implements GradingBloc {
  _FakeGradingBloc(this._state);
  final _controller = StreamController<GradingState>.broadcast();
  final GradingState _state;
  final List<GradingEvent> events = [];
  @override
  GradingState get state => _state;
  @override
  Stream<GradingState> get stream => _controller.stream;
  @override
  Future<void> close() async => _controller.close();
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
  libelle: 'Geste conforme',
  type: TypeCritere.binaire,
  ponderation: 10,
  valeurMax: 1,
  ordre: 1,
);
const _grille = Grille(id: 1, nom: 'G', noteMax: 10, items: [_item]);
const _amina =
    Etudiant(id: 1, nom: 'Trabelsi', prenom: 'Amina', numeroInscription: '1');
const _karim =
    Etudiant(id: 2, nom: 'Mzoughi', prenom: 'Karim', numeroInscription: '2');

GradingLoaded _etat({
  Duration? tempsRestant,
  Set<int> valides = const {},
}) =>
    GradingLoaded(
      rotationId: 1,
      stationId: 1,
      grilleId: 1,
      stationNom: 'Station',
      grille: _grille,
      lot: const Lot(
        id: 1,
        numero: 1,
        total: 3,
        etudiants: [_amina, _karim],
        groupeSuivantDisponible: true,
      ),
      notations: const {
        1: {7: Notation(etudiantId: 1, itemId: 7, valeur: 1)},
        2: {7: Notation(etudiantId: 2, itemId: 7, valeur: 1)},
      },
      etudiantsValides: valides,
      tempsRestant: tempsRestant,
    );

const _session = Session(
  id: 1,
  stationNom: 'Station',
  matiere: null,
  annee: null,
  statut: SessionStatus.enCours,
  heureDebut: '09:00',
  nbEtudiants: 2,
  salle: null,
  lotActuel: 1,
  totalLots: 3,
);

Future<_FakeGradingBloc> _pump(WidgetTester tester, GradingLoaded etat) async {
  final bloc = _FakeGradingBloc(etat);
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

Future<void> _tap(WidgetTester tester, String label) async {
  await tester.tap(find.text(label));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
}

void main() {
  testWidgets(
      '#423 — tout verrouillé, il reste du temps : aucune boîte, la validation part',
      (tester) async {
    final bloc = await _pump(tester,
        _etat(tempsRestant: const Duration(minutes: 2, seconds: 18), valides: {1, 2}));

    await _tap(tester, 'Valider groupe');

    expect(find.byType(AlertDialog), findsNothing,
        reason: 'le verrouillage a déjà eu sa confirmation ; ici il n\'y a plus rien à protéger');
    final valides = bloc.events.whereType<GradingGroupeValide>().toList();
    expect(valides.length, 1);
    expect(valides.single.puisAvancer, isFalse);
  });

  testWidgets('#423 — tout verrouillé, temps écoulé : aucune boîte, la validation part',
      (tester) async {
    final bloc = await _pump(
        tester, _etat(tempsRestant: const Duration(seconds: -30), valides: {1, 2}));

    await _tap(tester, 'Valider groupe');

    expect(find.byType(AlertDialog), findsNothing);
    expect(bloc.events.whereType<GradingGroupeValide>().length, 1);
  });

  testWidgets('#417/#423 — groupe incomplet : « Groupe incomplet », aucun événement',
      (tester) async {
    final bloc = await _pump(
        tester, _etat(tempsRestant: const Duration(minutes: 5), valides: {1}));

    await _tap(tester, 'Valider groupe');

    expect(find.text('Groupe incomplet'), findsOneWidget);
    expect(find.textContaining('Karim Mzoughi'), findsOneWidget);
    expect(bloc.events, isEmpty);
  });
}
