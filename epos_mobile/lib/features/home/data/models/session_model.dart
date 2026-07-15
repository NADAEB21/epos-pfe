// lib/features/home/data/models/session_model.dart

import '../../domain/entities/session.dart';

class SessionModel extends Session {
  const SessionModel({
    required super.id,
    super.stationId,              // nullable — absent des données mock
    required super.stationNom,
    super.matiere,
    super.annee,
    required super.statut,
    required super.heureDebut,
    required super.nbEtudiants,
    super.salle,
    required super.lotActuel,
    required super.totalLots,
    super.heureFin,
    super.avertissementLeadSec,
    super.enPause,
  });

  /// Parse la réponse de GET /api/v1/evaluateur/dashboard → sessions[].
  ///
  /// Correction point 3 : json['stationId'] est parsé séparément de json['id'].
  ///   json['id']        → lot.id (scoring-service)
  ///   json['stationId'] → station réelle (exam-service), nullable
  ///
  /// #196 (ADR-0012) : json['avertissementLeadSec'] et json['enPause'] sont
  /// déjà émis par SessionResponse (backend) mais n'étaient pas lus. Absents
  /// → valeurs neutres (0 / false) pour rester rétrocompatible avec un
  /// backend plus ancien ou avec les fixtures de test.
  factory SessionModel.fromJson(Map<String, dynamic> json) {
    return SessionModel(
      id:          json['id']          as int,
      stationId:   json['stationId']   as int?,   // ← nouveau, nullable
      stationNom:  json['stationNom']  as String? ?? '',
      matiere:     json['matiere']     as String? ?? '',
      annee:       json['annee']       as String?,
      statut:      _parseStatut(json['statut'] as String),
      heureDebut:  json['heureDebut']  as String,
      heureFin:    json['heureFin']    as String?,
      nbEtudiants: json['nbEtudiants'] as int,
      salle:       json['salle']       as String?,
      lotActuel:   json['lotActuel']   as int,
      totalLots:   json['totalLots']   as int,
      avertissementLeadSec: _toInt(json['avertissementLeadSec']),
      enPause:              json['enPause'] as bool? ?? false,
    );
  }

  static int _toInt(dynamic value) {
    if (value == null) return 0;
    if (value is int) return value;
    if (value is num) return value.toInt();
    if (value is String) return int.tryParse(value) ?? 0;
    return 0;
  }

  static SessionStatus _parseStatut(String statut) {
    switch (statut.toUpperCase()) {
      case 'EN_COURS':  return SessionStatus.enCours;
      case 'A_VENIR':   return SessionStatus.aVenir;
      case 'TERMINEE':
      case 'TERMINE':   return SessionStatus.terminee;
      default:          return SessionStatus.aVenir;
    }
  }
}

// ════════════════════════════════════════════════
// EvaluateurStatsModel — inchangé
// ════════════════════════════════════════════════
class EvaluateurStatsModel extends EvaluateurStats {
  const EvaluateurStatsModel({
    required super.sessionsAssignees,
    required super.totalEtudiants,
    required super.lotsValides,
    required super.totalLots,
  });

  factory EvaluateurStatsModel.fromJson(Map<String, dynamic> json) {
    return EvaluateurStatsModel(
      sessionsAssignees: json['sessionsAssignees'] as int,
      totalEtudiants:    json['totalEtudiants']    as int,
      lotsValides:       json['lotsValides']       as int,
      totalLots:         json['totalLots']         as int,
    );
  }
}

// ════════════════════════════════════════════════
// PlanningCellModel — inchangé
// ════════════════════════════════════════════════
class PlanningCellModel extends PlanningCell {
  const PlanningCellModel({
    required super.heure,
    required super.lotNumero,
    required super.statut,
  });

  factory PlanningCellModel.fromJson(Map<String, dynamic> json) {
    return PlanningCellModel(
      heure:     json['heure']     as String,
      lotNumero: json['lotNumero'] as int,
      statut:    _parseStatut(json['statut'] as String),
    );
  }

  static CellStatus _parseStatut(String statut) {
    switch (statut.toUpperCase()) {
      case 'TERMINE':  return CellStatus.termine;
      case 'A_VENIR':  return CellStatus.aVenir;
      case 'AUCUN':
      default:         return CellStatus.aucun;
    }
  }
}