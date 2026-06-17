// lib/features/home/domain/repositories/session_repository.dart

import '../entities/session.dart';

abstract class SessionRepository {
  /// Récupère toutes les sessions assignées à l'évaluateur connecté
  Future<List<Session>> getSessions();

  /// Récupère les stats du dashboard (sessions, étudiants, lots)
  Future<EvaluateurStats> getStats();

  /// Récupère le planning du jour (grille heure × lot)
  Future<List<PlanningCell>> getPlanningDuJour();
}