// lib/features/home/domain/repositories/session_repository.dart

import '../entities/session.dart';

abstract class SessionRepository {
  /// Récupère toutes les sessions assignées à l'évaluateur connecté
  Future<List<Session>> getSessions();

  /// Récupère les stats du dashboard (sessions, étudiants, lots)
  Future<EvaluateurStats> getStats();

  /// Récupère le planning du jour (grille heure × lot)
  Future<List<PlanningCell>> getPlanningDuJour();

  /// ADR-0012 — décalage horloge serveur ↔ appareil (serverNow − horloge
  /// locale), mesuré au dernier chargement du dashboard. Permet au calcul du
  /// temps restant de rester correct même si l'horloge/le fuseau de
  /// l'appareil diffère de celui du serveur (Africa/Tunis, cf. ClockConfig).
  Future<Duration> getClockOffset();
}