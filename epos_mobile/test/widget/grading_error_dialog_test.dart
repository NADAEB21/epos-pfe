// test/widget/grading_error_dialog_test.dart
// ================================================
// #383 — LE REFUS DE VERROUILLAGE SE LIT, IL NE DISPARAÎT PAS.
//
// PR #386 remplace le SnackBar transitoire par une boîte de dialogue
// bloquante : l'évaluateur doit fermer lui-même le message (« Compris »)
// après l'avoir lu. Ce test PUMPE le vrai GradingScreen (harnais fake-bloc
// à flux de grading_ghosting_test) et épingle le contrat :
//   1. messageErreur émis → AlertDialog affichée avec le texte VERBATIM ;
//   2. « Compris » la ferme — et l'écran de notation est toujours là,
//      saisies comprises (#248 : erreur NON fatale, jamais un écran perdu) ;
//   3. un état SANS messageErreur (le copyWith transitoire du bloc) ne
//      rouvre rien — pas d'empilement de dialogues.

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
    id: 7,
    libelle: 'Calcul de la masse',
    type: TypeCritere.numerique,
    ponderation: 1,
    valeurMax: 10,
    ordre: 1,
  );
  const grille =
      Grille(id: 1, nom: 'Grille test', noteMax: 10, items: [item]);
  const etudiant = Etudiant(
      id: 42, nom: 'Alpha', prenom: 'A', numeroInscription: '21/0042');

  final etatSain = GradingLoaded(
    rotationId: 1,
    stationId: 1,
    grilleId: 1,
    stationNom: 'Station test',
    grille: grille,
    lot: const Lot(id: 1, numero: 1, total: 2, etudiants: [etudiant]),
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

  const refus = 'Impossible de verrouiller : il reste 1 critère(s) non noté(s) '
      'pour A Alpha. Notez tous les critères, ou déclarez l\'étudiant absent.';

  testWidgets(
      '#383 — messageErreur → dialogue bloquant VERBATIM ; « Compris » le '
      'ferme et les saisies survivent (#248)', (tester) async {
    final bloc = _StreamFakeGradingBloc(etatSain);
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

    // L'évaluateur a saisi quelque chose AVANT le refus — la saisie doit
    // survivre au dialogue (#248 : erreur non fatale).
    await tester.enterText(find.byType(TextField), '5');
    await tester.pump();

    // Le bloc refuse le verrouillage (critères manquants).
    bloc.emitState(etatSain.copyWith(messageErreur: refus));
    await tester.pump();

    expect(find.byType(AlertDialog), findsOneWidget,
        reason: 'le refus doit se lire dans une boîte bloquante, pas un '
            'SnackBar transitoire (#383)');
    expect(find.text(refus), findsOneWidget,
        reason: 'le message du bloc est rendu VERBATIM');
    expect(find.text('Compris'), findsOneWidget);

    // Le copyWith transitoire du bloc (prochaine émission sans messageErreur)
    // ne doit PAS empiler un second dialogue.
    bloc.emitState(etatSain.copyWith(tempsRestant: const Duration(minutes: 5)));
    await tester.pump();
    expect(find.byType(AlertDialog), findsOneWidget,
        reason: 'une émission SANS messageErreur ne rouvre ni n\'empile');

    await tester.tap(find.text('Compris'));
    // Pas de pumpAndSettle : _PulsingDot anime en boucle et il ne « settle »
    // jamais (même raison que grading_ghosting_test). Deux pumps bornés
    // suffisent à jouer la fermeture du dialogue.
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300));

    expect(find.byType(AlertDialog), findsNothing);
    expect(find.byType(TextField), findsOneWidget,
        reason: 'l\'écran de notation est toujours là après le refus');
    expect(tester.widget<TextField>(find.byType(TextField)).controller?.text,
        '5',
        reason: 'la saisie faite avant le refus ne doit pas être perdue');
  });
}
