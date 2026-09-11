// test/widget/grading_suivant_confirm_test.dart
// ================================================
// #423 (recette du 06/09) — « GROUPE SUIVANT » NE SAUTE PLUS LA VALIDATION.
//
// #417 avait posé la boîte « Passer au groupe suivant maintenant ? » sur ce
// bouton : elle arrivait APRÈS le verrouillage et la validation, quand le temps
// restant ne pouvait plus rien changer (remarque de Nada). L'avertissement de
// temps vit désormais sur « Valider groupe » (grading_valider_confirm_test).
//
// Contrat :
//   1. groupe validé (lotValide) → AUCUNE boîte, même s'il reste du temps :
//      GradingGroupeSuivantDemande part directement ;
//   2. groupe complet mais non validé → boîte « Groupe non validé » ; « Rester
//      sur ce groupe » n'envoie rien ; « Valider puis passer » envoie
//      GradingGroupeValide(puisAvancer: true) — et PAS l'avance directe ;
//      AUCUNE mention de temps : les notes sont déjà verrouillées, l'acte
//      irréversible a eu sa confirmation (décision Nada, 06/09) ;
//   3. groupe incomplet → boîte « Groupe non validé » qui NOMME les étudiants
//      sans verdict, un seul bouton « Compris », aucun événement.

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
  bool lotValide = false,
}) =>
    GradingLoaded(
      rotationId: 1,
      stationId: 1,
      grilleId: 1,
      stationNom: 'Station',
      grille: _grille,
      lot: Lot(
        id: 1,
        numero: 1,
        total: 3,
        etudiants: const [_amina, _karim],
        valide: lotValide,
        groupeSuivantDisponible: true,
      ),
      // Tout le monde est noté : la seule chose qui manque est le verrou/verdict.
      notations: const {
        1: {7: Notation(etudiantId: 1, itemId: 7, valeur: 1)},
        2: {7: Notation(etudiantId: 2, itemId: 7, valeur: 1)},
      },
      etudiantsValides: valides,
      tempsRestant: tempsRestant,
      lotValide: lotValide,
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

Future<void> _tap(WidgetTester tester, String label) async {
  await tester.tap(find.text(label));
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 300));
}

void main() {
  testWidgets(
      '#423 — groupe validé : aucune boîte même s\'il reste du temps, l\'avance part',
      (tester) async {
    final bloc = await _pump(
        tester,
        _etat(
            tempsRestant: const Duration(minutes: 4, seconds: 7),
            valides: {1, 2},
            lotValide: true));

    await _tapSuivant(tester);

    expect(find.byType(AlertDialog), findsNothing,
        reason: 'les notes sont figées : un avertissement de temps ne changerait rien');
    expect(bloc.events.whereType<GradingGroupeSuivantDemande>().length, 1);
  });

  testWidgets(
      '#423 — complet mais non validé : « Groupe non validé » ; « Rester » n\'envoie '
      'rien ; « Valider puis passer » envoie GradingGroupeValide(puisAvancer)',
      (tester) async {
    final bloc = await _pump(tester,
        _etat(tempsRestant: const Duration(minutes: 4, seconds: 7), valides: {1, 2}));

    await _tapSuivant(tester);

    expect(find.text('Groupe non validé'), findsOneWidget);
    expect(find.textContaining('Il reste'), findsNothing,
        reason: 'les notes sont verrouillées : le temps ne change plus rien');
    expect(bloc.events, isEmpty);

    await _tap(tester, 'Rester sur ce groupe');
    expect(find.byType(AlertDialog), findsNothing);
    expect(bloc.events, isEmpty);

    await _tapSuivant(tester);
    await _tap(tester, 'Valider puis passer');
    final valides = bloc.events.whereType<GradingGroupeValide>().toList();
    expect(valides.length, 1);
    expect(valides.single.puisAvancer, isTrue,
        reason: 'l\'avance est enchaînée PAR le bloc, après le succès de la validation');
    expect(bloc.events.whereType<GradingGroupeSuivantDemande>(), isEmpty,
        reason: 'jamais d\'avance directe par-dessus un groupe non validé');
  });

  testWidgets(
      '#423 — complet, non validé, temps écoulé : même boîte (validation exigée)',
      (tester) async {
    final bloc = await _pump(
        tester, _etat(tempsRestant: const Duration(seconds: -12), valides: {1, 2}));

    await _tapSuivant(tester);

    expect(find.text('Groupe non validé'), findsOneWidget);
    expect(find.text('Valider puis passer'), findsOneWidget);
    expect(bloc.events, isEmpty);
  });

  testWidgets('#423 — groupe incomplet : le refus NOMME, sans option de passage',
      (tester) async {
    final bloc = await _pump(tester, _etat(tempsRestant: Duration.zero, valides: {1}));

    await _tapSuivant(tester);

    expect(find.text('Groupe non validé'), findsOneWidget);
    expect(find.textContaining('non verrouillé'), findsOneWidget);
    expect(find.textContaining('Karim Mzoughi'), findsOneWidget);
    expect(find.text('Compris'), findsOneWidget);
    expect(find.text('Valider puis passer'), findsNothing);
    expect(find.text('Passer quand même'), findsNothing,
        reason: 'plus aucun chemin ne laisse un groupe ouvert derrière soi');
    expect(bloc.events, isEmpty);
  });
}
