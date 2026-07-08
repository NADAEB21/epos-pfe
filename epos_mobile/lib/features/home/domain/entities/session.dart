// lib/features/home/domain/entities/session.dart

import 'package:equatable/equatable.dart';

enum SessionStatus { enCours, aVenir, terminee }

/// Représente une session d'évaluation assignée à l'évaluateur.
///
/// Correction point 3 : ajout de stationId (ID exam-service).
///
/// Distinction critique entre les deux identifiants :
///   id        → lot.id (scoring-service)
///              Utilisé par : getLot(stationId, lotNumero) via session.id
///              → GET /evaluateur/stations/{session.id}/lots/{lotNumero}
///
///   stationId → station réelle (exam-service)
///              Utilisé par : getGrille(stationId) dans GradingBloc
///              → GET /stations/{session.stationId}/grille
///
/// Sans cette distinction, GradingBloc passait lot.id à getGrille()
/// ce qui retournait un 404 car les IDs de lots ≠ IDs de stations.
class Session extends Equatable {
  final int           id;           // lot.id — scoring-service
  final int?          stationId;    // station réelle — exam-service ← nouveau
  final String        stationNom;
  final String?       matiere;
  final String?       annee;
  final SessionStatus statut;
  final String        heureDebut;
  final String?       heureFin;
  final int           nbEtudiants;
  final String?       salle;
  final int           lotActuel;
  final int           totalLots;

  const Session({
    required this.id,
    this.stationId,               // nullable pour compatibilité mock
    required this.stationNom,
    required this.matiere,
    required this.annee,
    required this.statut,
    required this.heureDebut,
    required this.nbEtudiants,
    required this.salle,
    required this.lotActuel,
    required this.totalLots,
    this.heureFin,
  });

  @override
  List<Object?> get props => [
    id, stationId, stationNom, matiere, annee, statut,
    heureDebut, nbEtudiants, salle, lotActuel, totalLots,
  ];
}

class PlanningCell extends Equatable {
  final String     heure;
  final int        lotNumero;
  final CellStatus statut;

  const PlanningCell({
    required this.heure,
    required this.lotNumero,
    required this.statut,
  });

  @override
  List<Object?> get props => [heure, lotNumero, statut];
}

enum CellStatus { termine, aVenir, aucun }

class EvaluateurStats extends Equatable {
  final int sessionsAssignees;
  final int totalEtudiants;
  final int lotsValides;
  final int totalLots;

  const EvaluateurStats({
    required this.sessionsAssignees,
    required this.totalEtudiants,
    required this.lotsValides,
    required this.totalLots,
  });

  @override
  List<Object?> get props =>
      [sessionsAssignees, totalEtudiants, lotsValides, totalLots];
}