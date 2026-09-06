// test/widget/grading_suivant_confirm_test.dart
// ================================================
// #417 (recette du 05/09) — AVANCER AVANT L'HEURE SE CONFIRME, NE SE BLOQUE PAS.
//
// ADR-0014 : l'horloge est un PLANCHER (le temps dû aux étudiants), jamais un
// plafond ; le guidage n'interdit rien. Nada a tranché à la recette : on
// AUTORISE de passer au groupe suivant avant la fin du créneau, mais l'écran
// doit le dire et demander confirmation. Idem quand des étudiants n'ont pas
// de verdict. Aucune raison → pas de boîte, l'avance part directement.
//
// Contrat :
//   1. tempsRestant > 0 → boîte « Passer au groupe suivant maintenant ? » avec
//      le temps restant (MM:SS) ; « Rester sur ce groupe » n'envoie rien ;
//      « Passer quand même » envoie GradingGroupeSuivantDemande ;
//   2. étudiants sans verdict → la raison est listée, nominativement ;
//   3. temps écoulé + tout validé → aucune boîte, l'événement part.

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

GradingLoaded _etat({Duration? tempsRestant, Set<int> valides = const {}}) =>
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
      notations: const {},
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

Future<void> _tapSuivant(WidgetTester tester) async {
  await tester.tap(find.text('Groupe suivant'));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
}

void main() {
  testWidgets(
      '#417 — il reste du temps : boîte avec MM:SS ; « Rester » n\'envoie rien, '
      '« Passer quand même » envoie l\'avance', (tester) async {
    final bloc = await _pump(tester,
        _etat(tempsRestant: const Duration(minutes: 4, seconds: 7), valides: {1, 2}));

    await _tapSuivant(tester);

    expect(find.text('Passer au groupe suivant maintenant ?'), findsOneWidget);
    expect(find.textContaining('dispose encore de 04:07'), findsOneWidget,
        reason: 'la raison dit le temps dû, en clair');
    expect(bloc.events.whereType<GradingGroupeSuivantDemande>(), isEmpty,
        reason: 'rien ne part tant que la confirmation n\'est pas donnée');

    await tester.tap(find.text('Rester sur ce groupe'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(find.byType(AlertDialog), findsNothing);
    expect(bloc.events.whereType<GradingGroupeSuivantDemande>(), isEmpty);

    await _tapSuivant(tester);
    await tester.tap(find.text('Passer quand même'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));
    expect(bloc.events.whereType<GradingGroupeSuivantDemande>().length, 1,
        reason: 'confirmer, jamais bloquer (ADR-0014)');
  });

  testWidgets('#417 — étudiants sans verdict : la raison les NOMME',
      (tester) async {
    final bloc = await _pump(tester, _etat(tempsRestant: Duration.zero, valides: {1}));

    await _tapSuivant(tester);

    expect(find.text('Passer au groupe suivant maintenant ?'), findsOneWidget);
    expect(find.textContaining('1 étudiant sans verdict : Karim Mzoughi.'), findsOneWidget);
    expect(find.textContaining('dispose encore de'), findsNothing,
        reason: 'temps écoulé : cette raison ne s\'affiche pas');
    expect(bloc.events.whereType<GradingGroupeSuivantDemande>(), isEmpty);
  });

  testWidgets(
      '#417 — temps écoulé et tout validé : aucune boîte, l\'avance part directement',
      (tester) async {
    final bloc = await _pump(
        tester, _etat(tempsRestant: const Duration(seconds: -12), valides: {1, 2}));

    await _tapSuivant(tester);

    expect(find.byType(AlertDialog), findsNothing);
    expect(bloc.events.whereType<GradingGroupeSuivantDemande>().length, 1);
  });
}
