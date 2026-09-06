// test/widget/grading_absent_visible_test.dart
// ================================================
// #417 (recette du 05/09) — L'ABSENCE SE VOIT DANS LA GRILLE ET S'Y DÉCLARE.
//
// Constat de Nada : un enseignant qui constate un absent à sa station ne
// trouvait pas où le déclarer (fiche → bascule → confirmer, deux écrans plus
// loin) ; il laissait la colonne vide et butait sur le refus de validation. Et
// un absent déclaré portait le MÊME avatar vert qu'un étudiant noté et
// verrouillé : la grille ne distinguait pas « présent, noté » de « absent ».
//
// Contrat :
//   1. etudiantsAbsents ∋ id → avatar gris + pastille « Absent » ; un étudiant
//      simplement verrouillé n'a pas la pastille ;
//   2. appui long sur un étudiant non validé → menu « Déclarer absent » →
//      confirmation → GradingEtudiantValide(id, absent: true) ;
//   3. le refus de validation (« Groupe incomplet ») distingue « Non noté »
//      de « Noté mais non verrouillé » et offre « Déclarer absent » aux non notés.

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

const _c1 = ItemEvaluation(
  id: 7, libelle: 'Geste conforme', type: TypeCritere.binaire,
  ponderation: 5, valeurMax: 1, ordre: 1,
);
const _c2 = ItemEvaluation(
  id: 8, libelle: 'Précision', type: TypeCritere.numerique,
  ponderation: 5, valeurMax: 5, ordre: 2,
);
const _grille = Grille(id: 1, nom: 'G', noteMax: 10, items: [_c1, _c2]);
const _amina = Etudiant(id: 1, nom: 'Trabelsi', prenom: 'Amina', numeroInscription: '1');
const _karim = Etudiant(id: 2, nom: 'Mzoughi', prenom: 'Karim', numeroInscription: '2');
const _nour  = Etudiant(id: 3, nom: 'Aloui',   prenom: 'Nour',  numeroInscription: '3');

GradingLoaded _etat({
  Set<int> valides = const {},
  Set<int> absents = const {},
  Map<int, Map<int, Notation>> notations = const {},
}) =>
    GradingLoaded(
      rotationId: 1, stationId: 1, grilleId: 1, stationNom: 'Station',
      grille: _grille,
      lot: const Lot(id: 1, numero: 1, total: 2, etudiants: [_amina, _karim, _nour],
          groupeSuivantDisponible: true),
      notations: notations,
      etudiantsValides: valides,
      etudiantsAbsents: absents,
      tempsRestant: Duration.zero,
    );

const _session = Session(
  id: 1, stationNom: 'Station', matiere: null, annee: null,
  statut: SessionStatus.enCours, heureDebut: '09:00', nbEtudiants: 3,
  salle: null, lotActuel: 1, totalLots: 2,
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

Future<void> _settle(WidgetTester tester) async {
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 350));
}

void main() {
  testWidgets('#417 — un absent porte la pastille « Absent » ; un verrouillé non',
      (tester) async {
    await _pump(tester, _etat(valides: {1, 2}, absents: {2}));

    expect(find.text('Absent'), findsOneWidget,
        reason: 'exactement UN absent dans ce groupe');
    // L'avatar de l'absent est gris ; celui du verrouillé (Amina) est vert.
    final avatars = tester.widgetList<CircleAvatar>(find.byType(CircleAvatar)).toList();
    expect(avatars.where((a) => a.backgroundColor == Colors.grey).length, 1);
  });

  testWidgets(
      '#417 — appui long → « Déclarer absent » → confirmation → GradingEtudiantValide(absent: true)',
      (tester) async {
    final bloc = await _pump(tester, _etat());

    await tester.longPress(find.text('Nour'));
    await _settle(tester);
    expect(find.text('Déclarer absent'), findsOneWidget,
        reason: 'l\'absence se déclare depuis la grille, sans détour par la fiche');

    await tester.tap(find.text('Déclarer absent'));
    await _settle(tester);
    expect(find.text('Marquer absent ?'), findsOneWidget);
    expect(find.textContaining('Nour Aloui sera marqué absent'), findsOneWidget);

    await tester.tap(find.byKey(const Key('confirmer-absence')));
    await _settle(tester);

    final envoyes = bloc.events.whereType<GradingEtudiantValide>().toList();
    expect(envoyes.length, 1);
    expect(envoyes.single.etudiantId, 3);
    expect(envoyes.single.absent, isTrue);
  });

  testWidgets(
      '#417 — « Groupe incomplet » distingue non noté / noté mais non verrouillé, '
      'et offre « Déclarer absent » aux non notés', (tester) async {
    // Amina : verrouillée. Karim : noté complet (2 critères) mais pas verrouillé.
    // Nour : 1 critère sur 2.
    final bloc = await _pump(
        tester,
        _etat(
          valides: {1},
          notations: {
            2: {
              7: const Notation(etudiantId: 2, itemId: 7, valeur: 1),
              8: const Notation(etudiantId: 2, itemId: 8, valeur: 3),
            },
            3: {7: const Notation(etudiantId: 3, itemId: 7, valeur: 1)},
          },
        ));

    await tester.tap(find.text('Valider groupe'));
    await _settle(tester);

    expect(find.text('Groupe incomplet'), findsOneWidget);
    expect(find.text('Non noté :'), findsOneWidget);
    expect(find.textContaining('Nour Aloui — 1 critère non noté'), findsOneWidget);
    expect(find.text('Noté mais non verrouillé :'), findsOneWidget);
    expect(find.textContaining('Karim Mzoughi'), findsOneWidget);
    expect(find.text('Déclarer absent'), findsOneWidget,
        reason: 'proposé pour le NON NOTÉ seulement (Karim est noté)');
    expect(bloc.events.whereType<GradingGroupeValide>(), isEmpty,
        reason: 'refus dur : rien ne part (#297)');

    await tester.tap(find.text('Déclarer absent'));
    await _settle(tester);
    expect(find.text('Marquer absent ?'), findsOneWidget);
  });
}
