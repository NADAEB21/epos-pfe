// test/widget/student_detail_restore_test.dart
// ================================================
// #335 — ROUVRIR LA FICHE D'UN ÉTUDIANT NE MENT PLUS.
//
// Avant : _absent=false et remarque vide codés en dur à l'ouverture — un
// étudiant déclaré ABSENT réapparaissait « Présent », sa remarque disparaissait,
// et re-verrouiller aurait écrasé la vérité serveur par les valeurs par défaut.
// Les notes, elles, étaient restaurées (notationExistante) : c'est ce qui
// rendait le piège invisible. Ce test fige la restauration des DEUX champs.

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/grading/domain/entities/grille.dart';
import 'package:epos_mobile/features/grading/domain/entities/lot.dart';
import 'package:epos_mobile/features/grading/domain/entities/notation.dart';
import 'package:epos_mobile/features/grading/presentation/bloc/grading_bloc.dart';
import 'package:epos_mobile/features/grading/presentation/screens/student_detail_screen.dart';

/// Bloc factice : l'écran ne fait que LIRE l'état — on lui sert un
/// GradingLoaded figé, sans repository ni réseau. (bloc_test est parké —
/// incompatibilité freezed/analyzer — d'où ce fake minimal.)
class _FakeGradingBloc extends Fake implements GradingBloc {
  _FakeGradingBloc(this._state);
  final GradingState _state;

  @override
  GradingState get state => _state;

  @override
  Stream<GradingState> get stream => const Stream.empty();

  @override
  Future<void> close() async {}

  @override
  void add(GradingEvent event) {}
}

void main() {
  const etudiantAbsent = Etudiant(
    id: 42,
    nom: 'Bel Haj',
    prenom: 'Sana',
    numeroInscription: '21/0042',
    absent: true,
    commentaire: 'Certificat médical présenté au surveillant.',
  );

  final state = GradingLoaded(
    rotationId: 1,
    stationId: 1,
    grilleId: 1,
    stationNom: 'Station test',
    grille: const Grille(id: 1, nom: 'Grille test', noteMax: 20, items: []),
    lot: const Lot(id: 1, numero: 1, total: 1, etudiants: [etudiantAbsent]),
    notations: const {},
    etudiantsValides: const {},
  );

  Future<void> pump(WidgetTester tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: BlocProvider<GradingBloc>.value(
          value: _FakeGradingBloc(state),
          child: const StudentDetailScreen(
            etudiant: etudiantAbsent,
            stationNom: 'Station test',
          ),
        ),
      ),
    );
    await tester.pump();
  }

  testWidgets('#335 — un étudiant ABSENT rouvre en Absent, pas en Présent',
      (tester) async {
    await pump(tester);

    // On lit le champ PUBLIC `absent` du toggle (find.byType est impossible
    // sur une classe privée — prédicat sur le runtimeType, leçon
    // « assert the WIDGET, not the text shape »).
    final toggle = find.byWidgetPredicate(
      (w) => w.runtimeType.toString() == '_PresenceToggle',
    );
    expect(toggle, findsOneWidget,
        reason: 'le toggle Présent/Absent doit être rendu');
    expect((tester.widget(toggle) as dynamic).absent, isTrue,
        reason: 'l’absence déclarée doit être RESTAURÉE à l’ouverture (#335)');
  });

  testWidgets('#335 — la remarque enregistrée est restaurée dans le champ',
      (tester) async {
    await pump(tester);

    final fields = tester
        .widgetList<TextField>(find.byType(TextField))
        .where((f) =>
            f.controller?.text == 'Certificat médical présenté au surveillant.');
    expect(fields.length, 1,
        reason: 'la remarque serveur doit pré-remplir le champ, pas un vide');
  });
}
