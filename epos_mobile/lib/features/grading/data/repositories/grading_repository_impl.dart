// lib/features/grading/data/repositories/grading_repository_impl.dart
// ================================================
// BF6.2 — Mise à jour du repository avec stratégie offline-first.
//
// PATCH par rapport à l'implémentation précédente :
//   saveNotation() adopte la stratégie "essaie en ligne, si échec réseau
//   → stocke localement". Compatible avec l'interface GradingRepository
//   existante — aucun changement de signature.
//
// Remplace entièrement grading_repository_impl.dart.

import 'package:dio/dio.dart';

import '../../../../core/constants/api_constants.dart';
import '../../../../core/network/api_client.dart';
import '../../../../core/offline/connectivity_service.dart';
import '../../../../core/offline/offline_storage_service.dart';
import '../../../../core/offline/sync_service.dart';
import '../../domain/entities/grille.dart';
import '../../domain/entities/lot.dart';
import '../../domain/entities/notation.dart';
import '../../domain/repositories/grading_repository.dart';
import '../models/grading_models.dart';

class GradingRepositoryImpl implements GradingRepository {
  final ApiClient _apiClient;

  GradingRepositoryImpl({required ApiClient apiClient})
      : _apiClient = apiClient;

  // ── GET /stations/{id}/grille ─────────────────────────────────────────────
  @override
  Future<Grille> getGrille(int stationId) async {
    try {
      final response = await _apiClient.get(
        ApiConstants.stationGrille(stationId),
      );
      final body = response.data as Map<String, dynamic>;
      final data = body['data'] as Map<String, dynamic>;
      return GrilleModel.fromJson(data);
    } on DioException catch (e) {
      throw _handleError(e, 'Impossible de charger la grille');
    }
  }

  // ── GET /evaluateur/stations/{id}/lots/{num} ──────────────────────────────
  @override
  Future<Lot> getLot(int stationId, int lotNumero) async {
    try {
      final response = await _apiClient.get(
        ApiConstants.lotDetail(stationId, lotNumero),
      );
      final body = response.data as Map<String, dynamic>;
      final data = body['data'] as Map<String, dynamic>;
      return LotModel.fromJson(data);
    } on DioException catch (e) {
      throw _handleError(e, 'Impossible de charger le lot');
    }
  }

  // ── POST /evaluateur/notations/saisir — stratégie offline-first ───────────
  //
  // Ordre de priorité :
  //   1. Si en ligne → envoi direct au backend
  //   2. Si hors-ligne ou erreur réseau → stockage SQLite local
  //
  // La synchronisation est ensuite gérée par SyncService à la reconnexion.
  @override
  Future<void> saveNotation(Notation notation) async {
    if (notation.stationId == null || notation.grilleId == null) return;

    final isOnline = ConnectivityService.instance.isOnline;

    if (isOnline) {
      try {
        await _apiClient.post(
          ApiConstants.saisirNotation,
          data: {
            'etudiantId': notation.etudiantId,
            'stationId':  notation.stationId,
            'grilleId':   notation.grilleId,
            'itemId':     notation.itemId,
            'valeur':     notation.valeur,
          },
        );
        // Succès en ligne : la notation est déjà en base sur le serveur,
        // on n'a pas besoin de la stocker localement.
        return;
      } on DioException catch (e) {
        // Erreur réseau → fallback local
        final isNetworkError =
            e.type == DioExceptionType.connectionError ||
            e.type == DioExceptionType.connectionTimeout ||
            e.type == DioExceptionType.receiveTimeout;

        if (!isNetworkError) {
          // Erreur métier (400, 403, 409…) → on la propage
          throw _handleError(e, 'Erreur lors de la sauvegarde de la notation');
        }
        // Réseau coupé → stockage local (voir ci-dessous)
      }
    }

    // Hors-ligne ou erreur réseau → persistance SQLite locale
    await OfflineStorageService.instance.upsertNotation(
      PendingNotation.fromNotation(notation),
    );
  }

  // ── Batch sync (appelé par SyncService) ──────────────────────────────────
  @override
  Future<void> saveNotations(List<Notation> notations) async {
    for (final n in notations) {
      await saveNotation(n);
    }
  }

  // ── POST /evaluateur/etudiants/{id}/stations/{id}/valider ─────────────────
  @override
  Future<void> validerEtudiant(
    int etudiantId,
    int stationId, {
    required int grilleId,
    bool    absent      = false,
    String? commentaire,
  }) async {
    try {
      await _apiClient.post(
        ApiConstants.validerEtudiant(etudiantId, stationId),
        data: {
          'grilleId':    grilleId,
          'absent':      absent,
          'commentaire': commentaire,
        },
      );
    } on DioException catch (e) {
      throw _handleError(e, "Impossible de valider l'étudiant");
    }
  }

  // ── POST /evaluateur/lots/{id}/valider ────────────────────────────────────
  @override
  Future<void> validerLot(int lotId) async {
    try {
      await _apiClient.post(ApiConstants.validerLot(lotId));
    } on DioException catch (e) {
      throw _handleError(e, 'Impossible de valider le lot');
    }
  }

  // ── Substitution ──────────────────────────────────────────────────────────
  @override
  Future<Etudiant> substituerEtudiant({
    required int lotId,
    required int etudiantAbsentId,
    required int etudiantRemplacantId,
  }) async {
    throw UnimplementedError(
      'substituerEtudiant sera implémenté au sprint 7 (BF3.4).',
    );
  }

  // ── Offline sync helpers ──────────────────────────────────────────────────
  @override
  Future<List<Notation>> getNotationsNonSynchro() async {
    final pending = await OfflineStorageService.instance.getPendingNotations();
    return pending.map((pn) => pn.toNotation()).toList();
  }

  @override
  Future<void> marquerSynchro(List<int> notationIds) async {
    await OfflineStorageService.instance.deleteByIds(notationIds);
  }

  // ── Gestion centralisée des erreurs ──────────────────────────────────────
  Exception _handleError(DioException e, String message) {
    switch (e.response?.statusCode) {
      case 401:
        return Exception('Session expirée. Veuillez vous reconnecter.');
      case 403:
        return Exception('Accès refusé.');
      case 404:
        return Exception('Ressource introuvable.');
      case 409:
        return Exception('Conflit : la notation existe déjà.');
      case 423:
        return Exception('Notes verrouillées. Demandez un déverrouillage.');
      case 500:
        return Exception('Erreur serveur. Réessayez plus tard.');
      default:
        if (e.type == DioExceptionType.connectionError ||
            e.type == DioExceptionType.connectionTimeout) {
          return Exception(
              'Hors ligne. Les données seront synchronisées à la reconnexion.');
        }
        return Exception('$message : ${e.message}');
    }
  }
}