// Relecture #328 (commit 5c11376) — le refus « déjà verrouillée » du backend
// (garde de re-verrou #297) est la PREUVE qu'un verrouillage antérieur a réussi
// (timeout après écriture serveur + retap) : le bloc doit ADOPTER l'état
// verrouillé au lieu de rollback l'optimisme. Toute autre erreur doit toujours
// rollback. Ces deux tests figent les deux branches du catch de
// _onEtudiantValide.
//
// Volontairement SANS bloc_test (parké, cf. *.parked.dart) : le bloc est piloté
// directement (seed via emit — @visibleForTesting — puis add + attente).

import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/grading/domain/entities/grille.dart';
import 'package:epos_mobile/features/grading/domain/entities/lot.dart';
import 'package:epos_mobile/features/grading/domain/entities/notation.dart';
import 'package:epos_mobile/features/grading/domain/repositories/grading_repository.dart';
import 'package:epos_mobile/features/grading/presentation/bloc/grading_bloc.dart';

/// Repository qui ne sait faire qu'une chose : échouer sur validerEtudiant
/// avec le message fourni. Tout autre appel est un bug du test.
class _FailingRepo implements GradingRepository {
  _FailingRepo(this.error);
  final Exception error;
  int validerCalls = 0;

  @override
  Future<void> validerEtudiant(
    int etudiantId,
    int stationId, {
    required int grilleId,
    bool absent = false,
    String? commentaire,
  }) async {
    validerCalls++;
    throw error;
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('inattendu dans ce test : ${invocation.memberName}');
}

GradingLoaded _loadedState() => GradingLoaded(
      rotationId: 272,
      stationId: 89,
      grilleId: 75,
      stationNom: 'Station test',
      // Grille sans feuille : la pré-garde locale #297 (critères manquants)
      // reste muette et on atteint bien l'appel réseau — c'est le catch
      // qu'on teste, pas la pré-garde.
      grille: const Grille(id: 75, nom: 'G', noteMax: 20, items: []),
      lot: const Lot(id: 1, numero: 1, total: 1, etudiants: [
        Etudiant(id: 42, nom: 'Dupont', prenom: 'Alice', numeroInscription: 'A1'),
      ]),
      notations: const {},
      etudiantsValides: const {},
    );

Future<void> _settle() => Future<void>.delayed(const Duration(milliseconds: 50));

void main() {
  test(
      '#328/5c11376 — refus « déjà verrouillée » = succès : '
      "l'étudiant RESTE validé, pas de bannière d'erreur", () async {
    final repo = _FailingRepo(Exception(
        'Notation déjà verrouillée pour Alice Dupont. '
        'Utilisez le canal de réajustement (réclamation) pour la modifier.'));
    final bloc = GradingBloc(repository: repo);
    // ignore: invalid_use_of_visible_for_testing_member
    bloc.emit(_loadedState());

    bloc.add(const GradingEtudiantValide(42));
    await _settle();

    final s = bloc.state as GradingLoaded;
    expect(repo.validerCalls, 1);
    expect(s.etudiantsValides, contains(42),
        reason: 'le refus de re-verrou prouve que le serveur EST verrouillé');
    expect(s.messageErreur, isNull,
        reason: 'adopter un état vrai ne doit afficher aucune erreur');
    await bloc.close();
  });

  test(
      '#328/5c11376 — toute AUTRE erreur rollback toujours '
      "l'optimisme et affiche le message", () async {
    final repo = _FailingRepo(Exception('Réseau injoignable'));
    final bloc = GradingBloc(repository: repo);
    // ignore: invalid_use_of_visible_for_testing_member
    bloc.emit(_loadedState());

    bloc.add(const GradingEtudiantValide(42));
    await _settle();

    final s = bloc.state as GradingLoaded;
    expect(s.etudiantsValides, isNot(contains(42)));
    expect(s.messageErreur, 'Réseau injoignable');
    await bloc.close();
  });
}
