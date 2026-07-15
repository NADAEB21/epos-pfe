// lib/features/grading/domain/repositories/grading_repository.dart

import '../entities/grille.dart';
import '../entities/lot.dart';
import '../entities/notation.dart';

abstract class GradingRepository {
  /// Charge la grille d'évaluation d'une station
  Future<Grille> getGrille(int stationId);

  /// Charge le lot actuel (étudiants + notations existantes)
  Future<Lot> getLot(int stationId, int lotNumero);

  /// Valide uniquement la rotation (session) de l'évaluateur actuel
  Future<void> validerRotation(int rotationId);

  /// Sauvegarde une notation (création ou mise à jour)
  Future<void> saveNotation(Notation notation);

  /// Sauvegarde plusieurs notations en batch (sync offline)
  Future<void> saveNotations(List<Notation> notations);

  /// Valide un étudiant (verrouille ses notes) ou le marque absent (score=0).
  Future<void> validerEtudiant(
  int etudiantId,
  int stationId, {
  required int grilleId,
  bool    absent      = false,
  String? commentaire,
  });

  /// Valide tout le lot (passe au lot suivant)
  Future<void> validerLot(int lotId);

  /// Substitue un étudiant absent
  Future<Etudiant> substituerEtudiant({
    required int lotId,
    required int etudiantAbsentId,
    required int etudiantRemplacantId,
  });

  /// Charge toutes les notations non synchronisées (mode offline)
  Future<List<Notation>> getNotationsNonSynchro();

  /// Marque les notations comme synchronisées
  Future<void> marquerSynchro(List<int> notationIds);
}