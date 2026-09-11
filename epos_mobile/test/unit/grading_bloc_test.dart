// test/unit/grading_bloc_test.dart
// ================================================
// Tests unitaires de GradingBloc — TOUS les événements.
//
// Volontairement SANS bloc_test (parké dans pubspec.yaml, cf. commentaire
// "DEMO LAN 2026-07-23" — conflit freezed/analyzer). On pilote le bloc
// directement : bloc.emit() (@visibleForTesting) pour semer un état de
// départ isolé, bloc.add() + un court settle() pour les effets async, puis
// on inspecte bloc.state. Même stratégie que
// grading_bloc_deja_verrouillee_test.dart, dont ce fichier reprend et
// complète les scénarios.
//
// Un GradingRepository factice (pas de mocktail — cohérent avec le reste de
// la suite) permet de piloter chaque appel réseau simulé et d'observer les
// appels effectués.

import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/grading/data/models/grading_models.dart';
import 'package:epos_mobile/features/grading/domain/entities/grille.dart';
import 'package:epos_mobile/features/grading/domain/entities/item_evaluation.dart';
import 'package:epos_mobile/features/grading/domain/entities/lot.dart';
import 'package:epos_mobile/features/grading/domain/entities/notation.dart';
import 'package:epos_mobile/features/grading/domain/repositories/grading_repository.dart';
import 'package:epos_mobile/features/grading/presentation/bloc/grading_bloc.dart';

// ── Doublure de repository — aucun réseau, entièrement pilotable ───────────
class _FakeGradingRepository implements GradingRepository {
  Grille? grille;
  Lot? lot;
  Object? getGrilleError;
  Object? getGroupeError;

  Future<void> Function(int etudiantId, int stationId,
      {required int grilleId, bool absent, String? commentaire})?
  onValiderEtudiant;
  Future<void> Function(int rotationId)? onValiderGroupe;
  Future<Lot> Function(int rotationId)? onGroupeSuivant;
  Future<Etudiant> Function()? onSubstituer;

  int validerEtudiantCalls = 0;
  int validerGroupeCalls = 0;
  int groupeSuivantCalls = 0;
  int saveNotationCalls = 0;

  @override
  Future<Grille> getGrille(int stationId) async {
    if (getGrilleError != null) throw getGrilleError!;
    return grille!;
  }

  @override
  Future<Lot> getGroupe(int rotationId) async {
    if (getGroupeError != null) throw getGroupeError!;
    return lot!;
  }

  @override
  Future<void> saveNotation(Notation notation) async {
    saveNotationCalls++;
  }

  @override
  Future<void> pushNotation(Notation notation) async {}

  @override
  Future<void> saveNotations(List<Notation> notations) async {}

  // #417 — journalise les effacements pour les assertions.
  final List<(int, int)> effaces = [];
  @override
  Future<void> effacerNotationItem({
    required int etudiantId,
    required int stationId,
    required int itemId,
  }) async {
    effaces.add((etudiantId, itemId));
  }

  @override
  Future<void> validerEtudiant(
      int etudiantId,
      int stationId, {
        required int grilleId,
        bool absent = false,
        String? commentaire,
      }) async {
    validerEtudiantCalls++;
    if (onValiderEtudiant != null) {
      await onValiderEtudiant!(etudiantId, stationId,
          grilleId: grilleId, absent: absent, commentaire: commentaire);
    }
  }

  @override
  Future<void> validerGroupe(int rotationId) async {
    validerGroupeCalls++;
    if (onValiderGroupe != null) await onValiderGroupe!(rotationId);
  }

  @override
  Future<Lot> getGroupeSuivant(int rotationId) async {
    groupeSuivantCalls++;
    if (onGroupeSuivant != null) return onGroupeSuivant!(rotationId);
    throw StateError('onGroupeSuivant non configuré');
  }

  @override
  Future<Etudiant> substituerEtudiant({
    required int lotId,
    required int etudiantAbsentId,
    required int etudiantRemplacantId,
  }) async {
    if (onSubstituer != null) return onSubstituer!();
    throw StateError('onSubstituer non configuré');
  }

  @override
  Future<List<Notation>> getNotationsNonSynchro() async => [];

  @override
  Future<void> marquerSynchro(List<int> notationIds) async {}
}

// ── Fixtures communes ───────────────────────────────────────────────────────
const _item1 = ItemEvaluation(
  id: 1, libelle: 'Critère 1', type: TypeCritere.binaire,
  ponderation: 2, valeurMax: 1, ordre: 1,
);
const _item2 = ItemEvaluation(
  id: 2, libelle: 'Critère 2', type: TypeCritere.numerique,
  ponderation: 6, valeurMax: 6, ordre: 2,
);
// Item avec sous-critères, pour exercer _trouverItemDansArbre (#160).
const _itemParent = ItemEvaluation(
  id: 100, libelle: 'Parent', type: TypeCritere.numerique,
  ponderation: 4, valeurMax: 0, ordre: 3,
  sousCriteres: [
    ItemEvaluation(
      id: 101, libelle: 'Enfant', type: TypeCritere.numerique,
      ponderation: 4, valeurMax: 4, ordre: 1,
    ),
  ],
);

const _etudiant1 = Etudiant(
  id: 1, nom: 'Karoui', prenom: 'Sonia', numeroInscription: '21/0001',
);
const _etudiant2 = Etudiant(
  id: 2, nom: 'Ben Ali', prenom: 'Nour', numeroInscription: '21/0002',
);

GradingLoaded _seedState({
  List<ItemEvaluation> items = const [_item1, _item2],
  Map<int, Map<int, Notation>> notations = const {},
  Set<int> etudiantsValides = const {},
  bool lotValide = false,
  bool groupeSuivantDisponible = false,
  List<Etudiant> etudiants = const [_etudiant1, _etudiant2],
  Lot? lot,
}) =>
    GradingLoaded(
      rotationId: 141,
      stationId: 5,
      grilleId: 10,
      stationNom: 'Station test',
      grille: Grille(id: 10, nom: 'Grille test', noteMax: 20, items: items),
      lot: lot ?? Lot(
        id: 28, numero: 1, total: 2, etudiants: etudiants,
        valide: lotValide, groupeSuivantDisponible: groupeSuivantDisponible,
      ),
      notations: notations,
      etudiantsValides: etudiantsValides,
      lotValide: lotValide,
    );

Future<void> _settle() => Future<void>.delayed(const Duration(milliseconds: 60));

void main() {
  group('GradingSessionStarted', () {
    test('succès — construit GradingLoaded à partir de la grille + du lot',
            () async {
          final repo = _FakeGradingRepository()
            ..grille = const Grille(id: 10, nom: 'G', noteMax: 8,
                items: [_item1, _item2])
            ..lot = const Lot(
              id: 28, numero: 1, total: 2,
              etudiants: [
                Etudiant(
                  id: 1, nom: 'Karoui', prenom: 'Sonia',
                  numeroInscription: '21/0001',
                  notationExistante: {1: 1.0},
                ),
                Etudiant(
                  id: 2, nom: 'Ben Ali', prenom: 'Nour',
                  numeroInscription: '21/0002',
                  absent: true,
                ),
              ],
              valide: false,
            );
          final bloc = GradingBloc(repository: repo);
          final states = <GradingState>[];
          final sub = bloc.stream.listen(states.add);

          bloc.add(const GradingSessionStarted(
            rotationId: 141, stationId: 5, lotNumero: 1,
            stationNom: 'Station test', grilleId: 10, dureeMinutes: 15,
          ));
          await _settle();
          await sub.cancel();

          expect(states, [isA<GradingLoading>(), isA<GradingLoaded>()]);
          final loaded = states[1] as GradingLoaded;
          expect(loaded.grille.items.length, 2);
          expect(loaded.notations[1]?[1]?.valeur, 1.0,
              reason: 'la notation existante de Sonia doit être restaurée');
          expect(loaded.etudiantsValides, contains(2),
              reason: 'un étudiant absent doit être considéré comme validé');
          expect(loaded.tempsRestant, const Duration(minutes: 15),
              reason: 'sans debutReel, le minuteur ouvre à la durée pleine');
          await bloc.close();
        });

    test('grilleId absent de l\'événement → retombe sur grille.id', () async {
      final repo = _FakeGradingRepository()
        ..grille = const Grille(id: 77, nom: 'G', noteMax: 20, items: [])
        ..lot = const Lot(id: 1, numero: 1, total: 1, etudiants: []);
      final bloc = GradingBloc(repository: repo);
      bloc.add(const GradingSessionStarted(
        rotationId: 1, stationId: 1, lotNumero: 1, stationNom: 'S',
      ));
      await _settle();
      final loaded = bloc.state as GradingLoaded;
      expect(loaded.grilleId, 77);
      await bloc.close();
    });

    test('échec réseau (grille OU groupe) → GradingError', () async {
      final repo = _FakeGradingRepository()
        ..getGrilleError = Exception('503');
      final bloc = GradingBloc(repository: repo);
      final states = <GradingState>[];
      final sub = bloc.stream.listen(states.add);

      bloc.add(const GradingSessionStarted(
        rotationId: 1, stationId: 1, lotNumero: 1, stationNom: 'S',
      ));
      await _settle();
      await sub.cancel();

      expect(states, [isA<GradingLoading>(), isA<GradingError>()]);
      expect((states[1] as GradingError).message, contains('503'));
      await bloc.close();
    });
  });

  group('GradingBinaryUpdated', () {
    late _FakeGradingRepository repo;
    late GradingBloc bloc;

    setUp(() {
      repo = _FakeGradingRepository();
      bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState());
    });
    tearDown(() => bloc.close());

    test('fait=true → valeur 1.0, et sauvegarde déclenchée', () async {
      bloc.add(const GradingBinaryUpdated(etudiantId: 1, itemId: 1, fait: true));
      await _settle();
      final s = bloc.state as GradingLoaded;
      expect(s.notations[1]?[1]?.valeur, 1.0);
      expect(repo.saveNotationCalls, 1);
    });

    test('fait=false → valeur 0.0', () async {
      bloc.add(const GradingBinaryUpdated(etudiantId: 1, itemId: 1, fait: false));
      await _settle();
      final s = bloc.state as GradingLoaded;
      expect(s.notations[1]?[1]?.valeur, 0.0);
    });

    test('fait=null → la notation est effacée localement, sans appel réseau',
            () async {
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(notations: {
            1: {1: const Notation(etudiantId: 1, itemId: 1, valeur: 1.0)},
          }));
          bloc.add(const GradingBinaryUpdated(etudiantId: 1, itemId: 1, fait: null));
          await _settle();
          final s = bloc.state as GradingLoaded;
          expect(s.notations[1]?.containsKey(1), isFalse);
          expect(repo.saveNotationCalls, 0);
        });

    test('étudiant déjà validé → la saisie est ignorée', () async {
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(etudiantsValides: {1}));
      bloc.add(const GradingBinaryUpdated(etudiantId: 1, itemId: 1, fait: true));
      await _settle();
      final s = bloc.state as GradingLoaded;
      expect(s.notations[1], isNull);
      expect(repo.saveNotationCalls, 0);
    });

    test('lot déjà validé (verrouillage global) → la saisie est ignorée',
            () async {
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(lotValide: true));
          bloc.add(const GradingBinaryUpdated(etudiantId: 1, itemId: 1, fait: true));
          await _settle();
          final s = bloc.state as GradingLoaded;
          expect(s.notations[1], isNull);
        });
  });

  // #417 (recette du 05/09) — une cellule VIDÉE ramène le critère à « non
  // noté ». Avant, l'écran envoyait GradingNumericUpdated(valeur: 0) : le
  // critère non noté devenait un zéro noté, passait la garde « il reste N
  // critère(s) » et pesait dans le total.
  group('GradingNumericCleared (#417)', () {
    late _FakeGradingRepository repo;
    late GradingBloc bloc;

    setUp(() {
      repo = _FakeGradingRepository();
      bloc = GradingBloc(repository: repo);
    });
    tearDown(() => bloc.close());

    test('retire la notation locale ET demande l\'effacement au dépôt', () async {
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(notations: {
        1: {2: const Notation(etudiantId: 1, itemId: 2, valeur: 3.0)},
      }));
      bloc.add(const GradingNumericCleared(etudiantId: 1, itemId: 2));
      await _settle();

      final s = bloc.state as GradingLoaded;
      expect(s.notations[1]!.containsKey(2), isFalse,
          reason: 'la cellule vidée n\'est plus une notation (ni 0, ni rien)');
      expect(repo.effaces, [(1, 2)]);
      // La garde de complétude compte désormais ce critère comme manquant.
      expect(s.etudiantComplet(1), isFalse);
    });

    test('rien à effacer (cellule déjà vide) → aucun appel au dépôt', () async {
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState());
      bloc.add(const GradingNumericCleared(etudiantId: 1, itemId: 2));
      await _settle();
      expect(repo.effaces, isEmpty);
    });

    test('étudiant verrouillé → ignoré (le verrou protège aussi l\'effacement)',
        () async {
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(
        notations: {1: {2: const Notation(etudiantId: 1, itemId: 2, valeur: 3.0)}},
        etudiantsValides: {1},
      ));
      bloc.add(const GradingNumericCleared(etudiantId: 1, itemId: 2));
      await _settle();
      final s = bloc.state as GradingLoaded;
      expect(s.notations[1]!.containsKey(2), isTrue);
      expect(repo.effaces, isEmpty);
    });
  });

  group('GradingNumericUpdated', () {
    late _FakeGradingRepository repo;
    late GradingBloc bloc;

    setUp(() {
      repo = _FakeGradingRepository();
      bloc = GradingBloc(repository: repo);
    });
    tearDown(() => bloc.close());

    test('la valeur est bornée par valeurMax de l\'item', () async {
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState());
      bloc.add(const GradingNumericUpdated(etudiantId: 1, itemId: 2, valeur: 99));
      await _settle();
      final s = bloc.state as GradingLoaded;
      expect(s.notations[1]?[2]?.valeur, 6.0,
          reason: 'item 2 a valeurMax = 6 : la saisie doit être clampée');
    });

    test('retrouve un sous-critère imbriqué (#160) et clampe sur SA valeurMax',
            () async {
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(items: const [_itemParent]));
          bloc.add(const GradingNumericUpdated(etudiantId: 1, itemId: 101, valeur: 999));
          await _settle();
          final s = bloc.state as GradingLoaded;
          expect(s.notations[1]?[101]?.valeur, 4.0);
        });

    test('étudiant validé → aucune mise à jour', () async {
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(etudiantsValides: {1}));
      bloc.add(const GradingNumericUpdated(etudiantId: 1, itemId: 2, valeur: 3));
      await _settle();
      final s = bloc.state as GradingLoaded;
      expect(s.notations[1], isNull);
    });
  });

  group('GradingEtudiantValide', () {
    test('critères manquants → refus local, messageErreur, pas d\'appel réseau',
            () async {
          final repo = _FakeGradingRepository();
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState()); // aucune notation saisie pour l'étudiant 1

          bloc.add(const GradingEtudiantValide(1));
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.etudiantsValides, isNot(contains(1)));
          expect(s.messageErreur, contains('2 critère'));
          expect(repo.validerEtudiantCalls, 0);
          await bloc.close();
        });

    test('#383 — lot issu du JSON réseau (List<EtudiantModel>) : le refus '
        's\'affiche au lieu de crasher', () async {
      // Le test « critères manquants » ci-dessus passait déjà sur le code
      // cassé : sa fixture construit une List<Etudiant> littérale. En
      // production, LotModel.fromJson produit une List<EtudiantModel> derrière
      // l'interface List<Etudiant>, et l'ancien firstWhere(orElse: ...) y
      // échouait en TypeError (vérification de covariance réifiée — VM ET
      // Web) : aucun messageErreur n'était jamais émis. On seed donc le lot
      // par le VRAI chemin de désérialisation.
      final lotReseau = LotModel.fromJson({
        'id': 28, 'numero': 1, 'total': 2, 'valide': false,
        'etudiants': [
          {'id': 1, 'nom': 'Karoui', 'prenom': 'Sonia',
            'numeroInscription': '21/0001'},
          {'id': 2, 'nom': 'Ben Ali', 'prenom': 'Nour',
            'numeroInscription': '21/0002'},
        ],
      });
      final repo = _FakeGradingRepository();
      final bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(lot: lotReseau)); // aucune notation saisie

      bloc.add(const GradingEtudiantValide(1));
      await _settle();

      final s = bloc.state as GradingLoaded;
      expect(s.messageErreur, contains('Sonia Karoui'),
          reason: 'le refus doit nommer l\'étudiant, pas crasher');
      expect(s.etudiantsValides, isNot(contains(1)));
      expect(repo.validerEtudiantCalls, 0);
      await bloc.close();
    });

    test('tous les critères saisis → validation envoyée et acceptée', () async {
      final repo = _FakeGradingRepository()
        ..onValiderEtudiant = (_, _, {required grilleId, absent = false, commentaire}) async {};
      final bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(notations: {
        1: {
          1: const Notation(etudiantId: 1, itemId: 1, valeur: 1),
          2: const Notation(etudiantId: 1, itemId: 2, valeur: 6),
        },
      }));

      bloc.add(const GradingEtudiantValide(1));
      await _settle();

      final s = bloc.state as GradingLoaded;
      expect(s.etudiantsValides, contains(1));
      expect(s.messageErreur, isNull);
      expect(repo.validerEtudiantCalls, 1);
      await bloc.close();
    });

    test('absent=true → jamais bloqué par les critères manquants, notations retirées',
            () async {
          final repo = _FakeGradingRepository()
            ..onValiderEtudiant = (_, _, {required grilleId, absent = false, commentaire}) async {
              expect(absent, isTrue);
            };
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(notations: {
            1: {1: const Notation(etudiantId: 1, itemId: 1, valeur: 1)},
          }));

          bloc.add(const GradingEtudiantValide(1, absent: true, commentaire: 'Certificat médical'));
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.etudiantsValides, contains(1));
          expect(s.notations.containsKey(1), isFalse);
          await bloc.close();
        });

    test('échec réseau générique → rollback de l\'optimisme + messageErreur',
            () async {
          final repo = _FakeGradingRepository()
            ..onValiderEtudiant = (_, _, {required grilleId, absent = false, commentaire}) async {
              throw Exception('Réseau injoignable');
            };
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(notations: {
            1: {
              1: const Notation(etudiantId: 1, itemId: 1, valeur: 1),
              2: const Notation(etudiantId: 1, itemId: 2, valeur: 6),
            },
          }));

          bloc.add(const GradingEtudiantValide(1));
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.etudiantsValides, isNot(contains(1)));
          expect(s.messageErreur, 'Réseau injoignable');
          await bloc.close();
        });

    test('refus « déjà verrouillée » → adopte l\'état validé (#328)', () async {
      final repo = _FakeGradingRepository()
        ..onValiderEtudiant = (_, _, {required grilleId, absent = false, commentaire}) async {
          throw Exception('Notation déjà verrouillée pour cet étudiant.');
        };
      final bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(notations: {
        1: {
          1: const Notation(etudiantId: 1, itemId: 1, valeur: 1),
          2: const Notation(etudiantId: 1, itemId: 2, valeur: 6),
        },
      }));

      bloc.add(const GradingEtudiantValide(1));
      await _settle();

      final s = bloc.state as GradingLoaded;
      expect(s.etudiantsValides, contains(1));
      expect(s.messageErreur, isNull);
      await bloc.close();
    });
  });

  group('GradingGroupeValide', () {
    test('succès — lotValide=true, message de succès, vagueTerminee dérivée',
            () async {
          final repo = _FakeGradingRepository()..onValiderGroupe = (_) async {};
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(groupeSuivantDisponible: false));

          bloc.add(const GradingGroupeValide());
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.lotValide, isTrue);
          expect(s.messageSucces, isNotNull);
          expect(s.lotEnCoursDeValidation, isFalse);
          expect(s.vagueTerminee, isTrue,
              reason: 'pas de groupe suivant → c\'est la fin de la vague pour cette station');
          await bloc.close();
        });

    test('un groupe suivant existe → vagueTerminee reste false', () async {
      final repo = _FakeGradingRepository()..onValiderGroupe = (_) async {};
      final bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(groupeSuivantDisponible: true));

      bloc.add(const GradingGroupeValide());
      await _settle();

      final s = bloc.state as GradingLoaded;
      expect(s.vagueTerminee, isFalse);
    });

    test('refus serveur (ex: groupe incomplet) → messageErreur, pas de crash',
            () async {
          final repo = _FakeGradingRepository()
            ..onValiderGroupe = (_) async => throw Exception('Groupe incomplet.');
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState());

          bloc.add(const GradingGroupeValide());
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.lotValide, isFalse);
          expect(s.lotEnCoursDeValidation, isFalse);
          expect(s.messageErreur, 'Groupe incomplet.');
          await bloc.close();
        });
  });

  group('GradingGroupeValide(puisAvancer) — #423 « Valider puis passer »', () {
    test('succès de la validation → la suite part et le nouveau groupe est chargé',
            () async {
          final repo = _FakeGradingRepository()
            ..onValiderGroupe = (_) async {}
            ..onGroupeSuivant = (rotationId) async => const Lot(
              id: 29, numero: 2, total: 2,
              etudiants: [_etudiant1, _etudiant2],
              valide: false,
            );
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(groupeSuivantDisponible: true, etudiantsValides: {1, 2}));

          bloc.add(const GradingGroupeValide(puisAvancer: true));
          await _settle();
          await _settle();

          expect(repo.validerGroupeCalls, 1);
          expect(repo.groupeSuivantCalls, 1, reason: 'validé, PUIS avancé');
          final s = bloc.state as GradingLoaded;
          expect(s.lot.id, 29);
          expect(s.lotValide, isFalse, reason: 'le nouveau groupe est non validé');
          await bloc.close();
        });

    test('refus serveur de la validation → AUCUNE avance, motif affiché', () async {
      final repo = _FakeGradingRepository()
        ..onValiderGroupe = (_) async => throw Exception('Groupe incomplet.');
      final bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(groupeSuivantDisponible: true));

      bloc.add(const GradingGroupeValide(puisAvancer: true));
      await _settle();
      await _settle();

      expect(repo.groupeSuivantCalls, 0,
          reason: 'jamais une avance par-dessus un groupe non validé');
      final s = bloc.state as GradingLoaded;
      expect(s.lotValide, isFalse);
      expect(s.messageErreur, 'Groupe incomplet.');
      await bloc.close();
    });

    test('pas de groupe suivant → validation seule, vagueTerminee, aucune avance appelée',
            () async {
          final repo = _FakeGradingRepository()..onValiderGroupe = (_) async {};
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(groupeSuivantDisponible: false));

          bloc.add(const GradingGroupeValide(puisAvancer: true));
          await _settle();
          await _settle();

          expect(repo.groupeSuivantCalls, 0);
          final s = bloc.state as GradingLoaded;
          expect(s.lotValide, isTrue);
          expect(s.vagueTerminee, isTrue);
          await bloc.close();
        });
  });

  group('GradingGroupeSuivantDemande', () {
    test('pas de groupe suivant → vagueTerminee=true, AUCUN appel réseau',
            () async {
          final repo = _FakeGradingRepository();
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(groupeSuivantDisponible: false));

          bloc.add(const GradingGroupeSuivantDemande());
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.vagueTerminee, isTrue);
          expect(repo.groupeSuivantCalls, 0);
          await bloc.close();
        });

    test('succès — nouveau GradingLoaded avec notations/validations remises à zéro',
            () async {
          final repo = _FakeGradingRepository()
            ..onGroupeSuivant = (rotationId) async => const Lot(
              id: 29, numero: 2, total: 2,
              etudiants: [_etudiant1, _etudiant2],
              valide: false,
            );
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(
            groupeSuivantDisponible: true,
            notations: {1: {1: const Notation(etudiantId: 1, itemId: 1, valeur: 1)}},
            etudiantsValides: {1},
          ));

          bloc.add(const GradingGroupeSuivantDemande());
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.lot.id, 29);
          expect(s.notations, isEmpty);
          expect(s.etudiantsValides, isEmpty);
          expect(s.tempsRestant, const Duration(minutes: 15),
              reason: 'nouveau groupe sans debutReel → durée pleine par défaut');
          await bloc.close();
        });

    test('échec réseau → l\'écran de notation reste affiché avec messageErreur',
            () async {
          final repo = _FakeGradingRepository()
            ..onGroupeSuivant = (rotationId) async => throw Exception('503');
          final bloc = GradingBloc(repository: repo);
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState(groupeSuivantDisponible: true));

          bloc.add(const GradingGroupeSuivantDemande());
          await _settle();

          final s = bloc.state as GradingLoaded;
          expect(s.lotEnCoursDeValidation, isFalse);
          expect(s.messageErreur, contains('Impossible de charger le groupe suivant'));
          // L'écran n'a PAS basculé sur un GradingError : les notes restent visibles.
          expect(bloc.state, isA<GradingLoaded>());
          await bloc.close();
        });
  });

  group('GradingEtudiantSubstitue', () {
    test('succès — remplace l\'étudiant absent dans le lot', () async {
      const remplacant = Etudiant(
        id: 99, nom: 'Ben Salah', prenom: 'Rim', numeroInscription: '21/0200',
      );
      final repo = _FakeGradingRepository()..onSubstituer = () async => remplacant;
      final bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(groupeSuivantDisponible: true));

      bloc.add(const GradingEtudiantSubstitue(
        etudiantAbsentId: 1, etudiantRemplacantId: 99,
      ));
      await _settle();

      final s = bloc.state as GradingLoaded;
      expect(s.lot.etudiants.any((e) => e.id == 99), isTrue);
      expect(s.lot.etudiants.any((e) => e.id == 1), isFalse);
      expect(s.lot.groupeSuivantDisponible, isTrue,
          reason: 'la substitution ne doit pas faire régresser #248');
      await bloc.close();
    });

    test('échec → GradingError (sortie de l\'écran de notation)', () async {
      final repo = _FakeGradingRepository()
        ..onSubstituer = () async => throw Exception('Aucun remplaçant');
      final bloc = GradingBloc(repository: repo);
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState());

      bloc.add(const GradingEtudiantSubstitue(
        etudiantAbsentId: 1, etudiantRemplacantId: 42,
      ));
      await _settle();

      expect(bloc.state, isA<GradingError>());
      await bloc.close();
    });
  });

  group('GradingTimerTick / GradingWsScoreReceived / GradingPauseStateUpdated', () {
    test('GradingTimerTick met à jour tempsRestant', () async {
      final bloc = GradingBloc(repository: _FakeGradingRepository());
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState());
      bloc.add(const GradingTimerTick(Duration(seconds: 42)));
      await _settle();
      expect((bloc.state as GradingLoaded).tempsRestant, const Duration(seconds: 42));
      await bloc.close();
    });

    test('GradingWsScoreReceived met à jour wsScores et verrouille si demandé',
            () async {
          final bloc = GradingBloc(repository: _FakeGradingRepository());
          // ignore: invalid_use_of_visible_for_testing_member
          bloc.emit(_seedState());
          bloc.add(const GradingWsScoreReceived(
            etudiantId: 1, stationId: 5, score: 14.5, verrouille: true,
          ));
          await _settle();
          final s = bloc.state as GradingLoaded;
          expect(s.wsScores[1], 14.5);
          expect(s.etudiantsValides, contains(1));
          await bloc.close();
        });

    test('scoreEtudiant priorise le score WebSocket sur le calcul local', () async {
      final bloc = GradingBloc(repository: _FakeGradingRepository());
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState(notations: {
        1: {1: const Notation(etudiantId: 1, itemId: 1, valeur: 1)},
      }));
      bloc.add(const GradingWsScoreReceived(
        etudiantId: 1, stationId: 5, score: 99, verrouille: false,
      ));
      await _settle();
      final s = bloc.state as GradingLoaded;
      expect(s.scoreEtudiant(1), 99);
      await bloc.close();
    });

    test('GradingPauseStateUpdated bascule enPause', () async {
      final bloc = GradingBloc(repository: _FakeGradingRepository());
      // ignore: invalid_use_of_visible_for_testing_member
      bloc.emit(_seedState());
      bloc.add(const GradingPauseStateUpdated(true));
      await _settle();
      expect((bloc.state as GradingLoaded).enPause, isTrue);
      await bloc.close();
    });
  });
}