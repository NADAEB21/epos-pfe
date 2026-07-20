// test/widget/timer_anchor_test.dart
//
// Finding #1 (session 19) — le compte à rebours de notation.
//
// ⚠️ LIRE AVANT DE MODIFIER L'ASSERTION.
// La formulation « naturelle » de ce test — *assert elapsed < dureeStationMin* —
// est PIÉGÉE : elle passe au vert sur le défaut actuel. Le bug d'aujourd'hui
// n'est pas « trop de temps écoulé », c'est « AUCUN temps écoulé » :
//
//   grading_bloc.dart:671   return _dureeStation; - elapsed;
//                                              ^ point-virgule parasite
//
// Le `return` se termine sur `_dureeStation` ; `- elapsed;` est une instruction
// morte (confirmé par `flutter analyze` : dead_code + unused_local_variable sur
// `elapsed` ET sur `effectiveNow`). `_computeTempsRestant` renvoie donc TOUJOURS
// la durée pleine de la station, quel que soit `debutCreneau`.
// Conséquence terrain : le minuteur repart à 15:00 à chaque réouverture — le
// symptôme même que #232/#234 prétendaient corriger.
//
// On assert donc que le temps écoulé EST bien soustrait (borne large, jamais
// dérivée de `now` au moment de l'assertion → pas de flakiness).
//
// Doctrine (README §3) : ceci est une assertion de PLANCHER (l'évaluateur voit
// le temps qui lui reste), pas de PLAFOND (on ne retire aucune session
// expirée). Elle est donc compatible ADR-0014 et ne cimente pas le chronomètre.
//
// NB anchor : le « bon » comportement s'ancre sur `debutCreneau` (l'horaire
// PRÉVU). Dès qu'un groupe avance hors planning (PACE ≠ PLAN), cet ancrage est
// lui-même discutable — il n'existe aujourd'hui aucun horodatage de début RÉEL
// (c'est le trou de #207). Ce test reste valable dans les deux cas : il vérifie
// seulement que la soustraction a lieu.

import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/grading/domain/entities/grille.dart';
import 'package:epos_mobile/features/grading/domain/entities/lot.dart';
import 'package:epos_mobile/features/grading/domain/repositories/grading_repository.dart';
import 'package:epos_mobile/features/grading/presentation/bloc/grading_bloc.dart';

// ── Doublure — aucun réseau ────────────────────────────────────────────────
// Calquée sur l'examen 2 / lot 28 / station 5 (eval3), grille 5 PLATE.
class _FakeGradingRepo implements GradingRepository {
  @override
  Future<Grille> getGrille(int stationId) async => const Grille(
        id: 5,
        nom: "Identification d'un principe actif",
        noteMax: 20,
        items: [],
      );

  @override
  Future<Lot> getGroupe(int rotationId) async => const Lot(
        id: 28,
        numero: 1,
        total: 4,
        etudiants: [],
      );

  @override
  dynamic noSuchMethod(Invocation i) => throw UnimplementedError();
}

/// Démarre une session et renvoie le premier `GradingLoaded` émis.
Future<GradingLoaded> _demarrer({
  required DateTime? debutCreneau,
  int dureeMinutes = 15,
}) async {
  final bloc = GradingBloc(repository: _FakeGradingRepo());
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
    debutCreneau: debutCreneau,
    dureeMinutes: dureeMinutes,
  ));

  final loaded = await futureLoaded as GradingLoaded;
  // close() annule le Timer.periodic démarré par _startTimer. Pas de
  // `testWidgets` ici, donc pas de FakeAsync : ce await ne peut pas figer
  // (cf. NEXT_SESSION.md — le piège `await bloc.close()` sous testWidgets).
  await bloc.close();
  return loaded;
}

void main() {
  group('finding #1 — ancrage du compte à rebours de passage', () {
    test(
      'un passage commencé il y a 6 min affiche MOINS que la durée pleine '
      '(le temps écoulé est réellement soustrait)',
      () async {
        // 6 min écoulées sur une station de 15 min → il doit rester ~9 min.
        // Borne large (< 12 min) : on veut discriminer « soustrait » de « pas
        // soustrait », pas mesurer la seconde exacte.
        final debut = DateTime.now().subtract(const Duration(minutes: 6));

        final loaded = await _demarrer(debutCreneau: debut, dureeMinutes: 15);

        expect(
          loaded.tempsRestant,
          isNotNull,
          reason: 'une session avec un debutCreneau doit porter un temps restant',
        );
        expect(
          loaded.tempsRestant!.inSeconds,
          lessThan(const Duration(minutes: 12).inSeconds),
          reason: 'ÉCHEC ATTENDU sur develop ed13a33 : grading_bloc.dart:671 '
              'renvoie _dureeStation sans soustraire elapsed (point-virgule '
              'parasite) → 15:00 au lieu de ~9:00. Le minuteur repart à zéro '
              'à chaque réouverture.',
        );
        // Borne basse : garde-fou contre une sur-correction (double soustraction).
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
        // #233/#234 ont introduit `dureeStationMin` côté serveur. Une station
        // de 20 min commencée il y a 6 min doit laisser ~14 min — impossible
        // à obtenir si la constante 15 est encore utilisée.
        final debut = DateTime.now().subtract(const Duration(minutes: 6));

        final loaded = await _demarrer(debutCreneau: debut, dureeMinutes: 20);

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
      'sans debutCreneau, on retombe sur la durée pleine (pas de crash)',
      () async {
        // Chemin nominal dégradé : aucun créneau connu → durée pleine.
        // C'est le SEUL cas où 15:00 est correct.
        final loaded = await _demarrer(debutCreneau: null, dureeMinutes: 15);

        expect(loaded.tempsRestant, const Duration(minutes: 15));
      },
    );
  });
}
