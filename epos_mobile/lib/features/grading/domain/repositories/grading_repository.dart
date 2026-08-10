// lib/features/grading/domain/repositories/grading_repository.dart

import '../entities/grille.dart';
import '../entities/lot.dart';
import '../entities/notation.dart';

abstract class GradingRepository {
  /// Charge la grille d'évaluation d'une station
  Future<Grille> getGrille(int stationId);

  /// Charge le groupe COURANT (= la rotation en cours). rotationId identifie
  /// sans ambiguïté (groupe × station × créneau).
  Future<Lot> getGroupe(int rotationId);

  /// Charge le PROCHAIN groupe planifié pour cet évaluateur (même station).
  Future<Lot> getGroupeSuivant(int rotationId);

  /// Valide uniquement la rotation (session) de l'évaluateur actuel
  /// Future<void> validerRotation(int rotationId);

  /// Sauvegarde une notation (création ou mise à jour).
  ///
  /// Chemin de SAISIE : « essaie en ligne, sinon garde en local ». Un échec
  /// réseau y est normal et silencieux — c'est le contrat du hors-ligne.
  Future<void> saveNotation(Notation notation);

  /// #307 — ENVOI PUR, réservé à la synchronisation. Ne retombe JAMAIS sur le
  /// stockage local et LÈVE une exception en cas d'échec.
  ///
  /// Pourquoi une seconde méthode : `saveNotation` avale les erreurs réseau et
  /// réenregistre en local, puis retourne normalement. La synchronisation
  /// interprétait ce retour comme un succès et supprimait la note — alors
  /// qu'elle n'était jamais partie. Pour synchroniser, « je n'ai pas pu » doit
  /// être une ERREUR, jamais un silence.
  Future<void> pushNotation(Notation notation);

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

  /// Verrouille les notes du groupe courant, clôture la rotation. Si c'était
  /// la dernière du lot, le lot est automatiquement clôturé côté serveur.
  Future<void> validerGroupe(int rotationId);

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