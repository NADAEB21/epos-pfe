// lib/features/grading/domain/entities/notation.dart

import 'package:equatable/equatable.dart';

/// Représente la saisie d'une note pour un critère d'un étudiant.
///
/// stationId et grilleId sont nécessaires pour que le backend puisse
/// retrouver la chaîne Notation → RotationAssignment → ExamenParticipation.
/// Ils correspondent aux champs attendus par SaisirNotationRequest (backend).
class Notation extends Equatable {
  final int    etudiantId;
  final int    itemId;
  final double valeur;
  final bool   verrouille;
  // Ajoutés pour le backend réel — nullables pour compatibilité
  // avec le mock existant (MockGradingRepository ne les utilise pas).
  final int?   stationId;
  final int?   grilleId;

  const Notation({
    required this.etudiantId,
    required this.itemId,
    required this.valeur,
    this.verrouille = false,
    this.stationId,
    this.grilleId,
  });

  Notation copyWith({
    double? valeur,
    bool?   verrouille,
    int?    stationId,
    int?    grilleId,
  }) => Notation(
    etudiantId: etudiantId,
    itemId:     itemId,
    valeur:     valeur      ?? this.valeur,
    verrouille: verrouille  ?? this.verrouille,
    stationId:  stationId   ?? this.stationId,
    grilleId:   grilleId    ?? this.grilleId,
  );

  @override
  List<Object?> get props =>
      [etudiantId, itemId, valeur, verrouille, stationId, grilleId];
}

class Etudiant extends Equatable {
  final int    id;
  final String nom;
  final String prenom;
  final String numeroInscription;
  final int?   numeroEchantillon; 
  // Champs ajoutés pour porter la progression existante depuis le backend
  final bool             absent;
  final bool   verrouille; 
  final String?          commentaire; 
  /// Map itemId → valeur déjà saisie (chargée depuis la DB au rechargement).
  final Map<int, double> notationExistante;

  const Etudiant({
    required this.id,
    required this.nom,
    required this.prenom,
    required this.numeroInscription,
    this.numeroEchantillon,
    this.absent = false,
    this.verrouille = false,
    this.commentaire,
    this.notationExistante = const {},
  });

  String get initiales  => '${prenom[0]}${nom[0]}'.toUpperCase();
  String get nomComplet => '$prenom $nom';

  @override
  List<Object?> get props =>
      [id, nom, prenom, numeroInscription, numeroEchantillon, absent, verrouille, commentaire];
}