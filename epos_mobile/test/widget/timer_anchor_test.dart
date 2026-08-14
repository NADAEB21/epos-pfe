// test/widget/timer_anchor_test.dart
//
// Finding #1 (session 19), RÉÉCRIT le 2026-08-14 (#333) contre le comportement
// ACTUEL du minuteur.
//
// ⚠️ CE QUI A CHANGÉ DEPUIS LA VERSION D'ORIGINE DE CE TEST.
// À l'origine ce test ancrait le compte à rebours sur `debutCreneau` (l'horaire
// PLANIFIÉ), passé directement dans l'événement `GradingSessionStarted`. Depuis
// #209 (ADR-0014-B), l'ancre du minuteur n'est plus le créneau planifié — c'est
// `Rotation.debut_reel`, l'instant RÉEL où l'évaluateur a ouvert le groupe,
// renvoyé par le serveur sur le `Lot` (voir grading_bloc.dart :
// `_computeTempsRestant(lot.debutReel)`, plus jamais `event.debutCreneau`).
//
// Le test échouait pour la MAUVAISE raison : il posait toujours `debutCreneau`
// sur l'événement, une valeur que le bloc ignore désormais pour ce calcul.
// `_FakeGradingRepo.getGroupe` renvoyait un `Lot` sans `debutReel` (donc null)
// ⇒ repli systématique sur la durée pleine. Pas une régression : un ancrage
// abandonné. On pose maintenant `debutReel` sur le `Lot` du repository
// factice — exactement ce que fait le vrai backend depuis #209.
//
// Doctrine (README §3) : assertion de PLANCHER, jamais de PLAFOND.

import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/grading/domain/entities/grille.dart';
import 'package:epos_mobile/features/grading/domain/entities/lot.dart';
import 'package:epos_mobile/features/grading/domain/repositories/grading_repository.dart';
import 'package:epos_mobile/features/grading/presentation/bloc/grading_bloc.dart';

// ── Doublure — aucun réseau ────────────────────────────────────────────────
class _FakeGradingRepo implements GradingRepository {
  _FakeGradingRepo({this.debutReel});

  /// #209 — l'ancre RÉELLE du minuteur, portée par le Lot (jamais plus par
  /// l'événement de session).
  final DateTime? debutReel;

  @override
  Future<Grille> getGrille(int stationId) async => const Grille(
    id: 5,
    nom: "Identification d'un principe actif",
    noteMax: 20,
    items: [],
  );

  @override
  Future<Lot> getGroupe(int rotationId) async => Lot(
    id: 28,
    numero: 1,
    total: 4,
    etudiants: const [],
    debutReel: debutReel,
  );

  @override
  dynamic noSuchMethod(Invocation i) => throw UnimplementedError();
}

/// Démarre une session et renvoie le premier `GradingLoaded` émis.
Future<GradingLoaded> _demarrer({
  required DateTime? debutReel,
  int dureeMinutes = 15,
}) async {
  final bloc = GradingBloc(repository: _FakeGradingRepo(debutReel: debutReel));
  final futureLoaded =
  bloc.stream.firstWhere((s) => s is GradingLoaded).timeout(
    const Duration(seconds: 10),
  );

  bloc.add(GradingSessionStarted(
    rotationId: 141,
    stationId: 5,
    lotNumero: 1,
    stationNom: "Identification d'un principe actif",
    grilleId: 5,
    dureeMinutes: dureeMinutes,
  ));

  final loaded = await futureLoaded as GradingLoaded;
  // close() annule le Timer.periodic démarré par _startTimer. Pas de
  // `testWidgets` ici, donc pas de FakeAsync : cet await ne peut pas figer.
  await bloc.close();
  return loaded;
}

void main() {
  group('#209 — ancrage du compte à rebours sur debut_reel', () {
    test(
      'un groupe ouvert il y a 6 min affiche MOINS que la durée pleine '
          '(le temps écoulé est réellement soustrait)',
          () async {
        final ouvertureReelle =
        DateTime.now().subtract(const Duration(minutes: 6));

        final loaded =
        await _demarrer(debutReel: ouvertureReelle, dureeMinutes: 15);

        expect(loaded.tempsRestant, isNotNull,
            reason: 'un groupe avec debutReel doit porter un temps restant');
        expect(
          loaded.tempsRestant!.inSeconds,
          lessThan(const Duration(minutes: 12).inSeconds),
          reason: 'le temps écoulé depuis debutReel doit être soustrait de '
              'la durée pleine.',
        );
        expect(
          loaded.tempsRestant!.inSeconds,
          greaterThan(const Duration(minutes: 6).inSeconds),
          reason: 'sur-correction : il ne doit pas rester moins de ~9 min',
        );
      },
    );

    test(
      'la durée de station vient du serveur (dureeMinutes), pas de la constante 15',
          () async {
        final ouvertureReelle =
        DateTime.now().subtract(const Duration(minutes: 6));

        final loaded =
        await _demarrer(debutReel: ouvertureReelle, dureeMinutes: 20);

        expect(
          loaded.tempsRestant!.inSeconds,
          greaterThan(const Duration(minutes: 12).inSeconds),
          reason: 'une station de 20 min doit laisser plus de temps '
              "qu'une station de 15 min",
        );
        expect(
          loaded.tempsRestant!.inSeconds,
          lessThan(const Duration(minutes: 17).inSeconds),
          reason: 'le temps écoulé doit rester soustrait, durée serveur ou non',
        );
      },
    );

    test(
      'sans debutReel (groupe jamais ouvert), on retombe sur la durée pleine',
          () async {
        final loaded = await _demarrer(debutReel: null, dureeMinutes: 15);
        expect(loaded.tempsRestant, const Duration(minutes: 15));
      },
    );
  });
}