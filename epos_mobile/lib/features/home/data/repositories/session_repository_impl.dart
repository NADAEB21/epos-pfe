// lib/features/home/data/repositories/session_repository_impl.dart

import 'package:dio/dio.dart';

import '../../../../core/constants/api_constants.dart';
import '../../../../core/network/api_client.dart';
import '../../domain/entities/session.dart';
import '../../domain/repositories/session_repository.dart';
import '../models/session_model.dart';

/// Implémentation réelle du SessionRepository.
///
/// Correction décalage D : les 3 anciens endpoints inexistants
/// (sessions, stats, planning) sont remplacés par un seul appel
/// GET /api/v1/evaluateur/dashboard qui retourne les 3 en une fois.
///
/// Structure de la réponse backend (EvaluateurDashboardResponse) :
/// {
///   "success": true,
///   "data": {
///     "sessions": [...],
///     "stats":    { "sessionsAssignees": 3, ... },
///     "planning": [...]
///   }
/// }
class SessionRepositoryImpl implements SessionRepository {
  final ApiClient _apiClient;

  // Cache en mémoire pour éviter 3 appels réseau redondants.
  // Invalidé à chaque appel de getSessions() (refresh ou premier chargement).
  Map<String, dynamic>? _cachedDashboard;

  Duration _clockOffset = Duration.zero;

  SessionRepositoryImpl({required ApiClient apiClient})
      : _apiClient = apiClient;

  // ── Appel unique au dashboard ─────────────────────────────────────────────

  /// Charge le dashboard complet depuis le backend et le met en cache.
  /// Tous les getters (getSessions, getStats, getPlanningDuJour) utilisent ce cache.
  Future<Map<String, dynamic>> _fetchDashboard() async {
    if (_cachedDashboard != null) return _cachedDashboard!;

    try {
      final response = await _apiClient.get(ApiConstants.dashboard);
      final body = response.data as Map<String, dynamic>;
      // Déballe l'enveloppe ApiResponse<EvaluateurDashboardResponse>
      _cachedDashboard = body['data'] as Map<String, dynamic>;

      // ADR-0012 : capture le décalage serveur ↔ appareil. serverNow est
      // sérialisé "yyyy-MM-dd HH:mm:ss" (sans zone), donc DateTime.parse
      // l'interprète comme une heure "locale" de l'appareil — techniquement
      // faux si l'appareil n'est pas sur Africa/Tunis. Mais debutPrevu subit
      // EXACTEMENT la même interprétation "fake local" (même motif, même
      // source) : l'erreur de fuseau s'annule dans la soustraction, et seul
      // l'écart RÉEL entre les deux horloges (dérive, latence) survit.
      final rawServerNow = _cachedDashboard?['serverNow'] as String?;
      if (rawServerNow != null) {
        final parsed = parseServerTimestamp(rawServerNow);
        if (parsed != null) {
          _clockOffset = parsed.difference(DateTime.now());
        }
      }
      return _cachedDashboard!;
    } on DioException catch (e) {
      throw _handleDioError(e, 'Impossible de charger le dashboard');
    }
  }

  /// Invalide le cache pour forcer un rechargement au prochain appel.
  void _invalidateCache() => _cachedDashboard = null;

  // ── getSessions ───────────────────────────────────────────────────────────

  @override
  Future<List<Session>> getSessions() async {
    _invalidateCache(); // Toujours recharger depuis le réseau
    final dashboard = await _fetchDashboard();

    final List<dynamic> sessionsJson =
        dashboard['sessions'] as List<dynamic>? ?? [];

    return sessionsJson
        .map((json) => SessionModel.fromJson(json as Map<String, dynamic>))
        .toList();
  }

  // ── getStats ──────────────────────────────────────────────────────────────

  @override
  Future<EvaluateurStats> getStats() async {
    final dashboard = await _fetchDashboard();

    final statsJson =
        dashboard['stats'] as Map<String, dynamic>? ?? {};

    return EvaluateurStatsModel.fromJson(statsJson);
  }

  // ── getPlanningDuJour ─────────────────────────────────────────────────────

  @override
  Future<List<PlanningCell>> getPlanningDuJour() async {
    final dashboard = await _fetchDashboard();

    final List<dynamic> planningJson =
        dashboard['planning'] as List<dynamic>? ?? [];

    return planningJson
        .map((json) =>
            PlanningCellModel.fromJson(json as Map<String, dynamic>))
        .toList();
  }

  @override
  Future<Duration> getClockOffset() async {
    await _fetchDashboard();
    return _clockOffset;
  }

  // ── Gestion des erreurs ───────────────────────────────────────────────────

  Exception _handleDioError(DioException e, String defaultMessage) {
    switch (e.response?.statusCode) {
      case 401:
        return Exception('Session expirée. Veuillez vous reconnecter.');
      case 403:
        return Exception("Accès refusé. Vous n'êtes pas autorisé.");
      case 404:
        return Exception('Ressource introuvable sur le serveur.');
      case 500:
        return Exception('Erreur interne du serveur. Réessayez plus tard.');
      default:
        if (e.type == DioExceptionType.connectionTimeout ||
            e.type == DioExceptionType.receiveTimeout) {
          return Exception('Délai dépassé. Vérifiez votre connexion réseau.');
        }
        if (e.type == DioExceptionType.connectionError) {
          return Exception(
            'Impossible de joindre le serveur. '
            'Vérifiez que le backend Spring Boot est démarré.',
          );
        }
        return Exception('$defaultMessage : ${e.message}');
    }
  }

  /// Parse un timestamp serveur "yyyy-MM-dd HH:mm:ss" (pausedAt, launchedAt,
  /// debutPrevu, serverNow — même motif partout). Partagé pour rester cohérent.
  DateTime? parseServerTimestamp(String raw) {
    try {
      return DateTime.parse(raw.replaceFirst(' ', 'T'));
    } catch (_) {
      return null;
    }
  }
}