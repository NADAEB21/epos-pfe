// #409 — la grille de notation REMPLIT la largeur de l'écran, sur un téléphone
// en portrait comme en paysage, avec 2 ou 6 étudiants : la dernière colonne
// atteint le bord (ou dépasse et défile), jamais une bande nue à droite.
//
// Aucun test ne fixait une taille d'écran de téléphone : la branche
// « téléphone » (< 600 dp) n'était jamais exécutée par la suite.

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
  @override
  GradingState get state => _state;
  @override
  Stream<GradingState> get stream => _controller.stream;
  @override
  Future<void> close() async {
    await _controller.close();
  }
  @override
  void add(GradingEvent event) {}
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
    id: 1,
    libelle: 'Pesée',
    type: TypeCritere.numerique,
    ponderation: 10,
    valeurMax: 10,
    ordre: 1,
  );
  const grille = Grille(id: 1, nom: 'Grille test', noteMax: 10, items: [item]);

  List<Etudiant> etudiants(int n) => List.generate(
        n,
        (i) => Etudiant(id: 100 + i, nom: 'Nom$i', prenom: 'P', numeroInscription: '21/00$i'),
      );

  GradingLoaded etat(int n) => GradingLoaded(
        rotationId: 1,
        stationId: 1,
        grilleId: 1,
        stationNom: 'Station test',
        grille: grille,
        lot: Lot(id: 1, numero: 1, total: 2, etudiants: etudiants(n)),
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

  Future<void> pumpAt(WidgetTester tester, Size size, int n) async {
    tester.view.physicalSize = size;
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final bloc = _FakeGradingBloc(etat(n));
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
  }

  for (final cas in [
    (Size(390, 844), 2, 'téléphone portrait, 2 étudiants'),
    (Size(390, 844), 6, 'téléphone portrait, 6 étudiants (déborde et défile)'),
    (Size(844, 390), 4, 'téléphone paysage, 4 étudiants'),
    (Size(800, 1280), 3, 'tablette, 3 étudiants'),
  ]) {
    testWidgets('#409 — la grille atteint le bord droit : ${cas.$3}', (tester) async {
      await pumpAt(tester, cas.$1, cas.$2);
      final grid = find.byKey(const Key('grading-grid'));
      expect(grid, findsOneWidget);
      final largeur = tester.getSize(grid).width;
      expect(
        largeur,
        greaterThanOrEqualTo(cas.$1.width - 1),
        reason: 'contenu ${largeur}px pour un écran de ${cas.$1.width}px — bande nue à droite',
      );
      expect(tester.takeException(), isNull, reason: 'aucun débordement (RenderFlex overflow)');
    });
  }
}
