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

import 'dart:async' show unawaited;
import 'dart:convert';

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

  // ── GET /stations/{id}/grille — offline-first (#244) ────────────
  //
  // Chemin nominal inchangé : appel réseau, parsing. Deux ajouts :
  //   1. Succès  → écrit le body brut en cache local (best-effort, jamais
  //      bloquant — même posture que _memoriserLibelles dans grading_bloc.dart).
  //   2. Échec RÉSEAU (pas une erreur métier 4xx/5xx) → retombe sur la
  //      dernière grille mise en cache pour CETTE station, si elle existe.
  //      Sinon, l'erreur d'origine remonte inchangée : ne jamais inventer
  //      une grille vide plutôt que de dire clairement "rien en cache".
  @override
  Future<Grille> getGrille(int stationId) async {
    try {
      final response = await _apiClient.get(
        ApiConstants.stationGrille(stationId),
      );
      final body = response.data as Map<String, dynamic>;
      final data = body['data'] as Map<String, dynamic>;

      final grille = GrilleModel.fromJson(data);
      // Écrit APRÈS un parsing réussi : ne jamais figer en cache un payload
      // qu'on ne sait pas relire.
      unawaited(_cacheGrilleSafely(stationId, data));
      return grille;
    } on DioException catch (e) {
      final statusCode = e.response?.statusCode;
      // Transitoire = indisponibilité SERVEUR (réseau coupé, ou 5xx — le cas
      // réellement reproduit dans #244 : 503 renvoyé par la gateway quand
      // exam-service est arrêté). Jamais un 4xx : un refus métier (403 non
      // affecté, 404 introuvable) doit rester une vraie erreur, pas un repli
      // silencieux vers une grille potentiellement obsolète.
      final isTransient =
          e.type == DioExceptionType.connectionError ||
              e.type == DioExceptionType.connectionTimeout ||
              e.type == DioExceptionType.receiveTimeout ||
              (e.type == DioExceptionType.badResponse &&
                  statusCode != null && statusCode >= 500);

      if (isTransient) {
        try {
          final cached = await OfflineStorageService.instance.getCachedGrille(stationId);
          if (cached != null) {
            final decoded = jsonDecode(cached.grilleJson) as Map<String, dynamic>;
            return GrilleModel.fromJson(decoded, depuisCache: true);
          }
        } catch (_) {
          // Cache indisponible/corrompu : on retombe sur l'erreur réseau
          // d'origine plutôt que de propager une exception de second ordre.
        }
      }
      throw _handleError(e, 'Impossible de charger la grille');
    }
  }

  /// Écrit la grille en cache. Silencieux par conception : un échec d'écriture
  /// de CACHE ne doit jamais transformer un chargement réussi en erreur pour
  /// l'évaluateur (même principe que _memoriserLibelles dans grading_bloc.dart).
  Future<void> _cacheGrilleSafely(int stationId, Map<String, dynamic> data) async {
    try {
      await OfflineStorageService.instance.cacheGrille(stationId, jsonEncode(data));
    } catch (_) {
      // Confort hors-ligne uniquement.
    }
  }

  @override
  Future<Lot> getGroupe(int rotationId) async {
  try {
    final response = await _apiClient.get(ApiConstants.groupeDetail(rotationId));
    final data = (response.data as Map<String, dynamic>)['data'] as Map<String, dynamic>;
    return LotModel.fromJson(data);
  } on DioException catch (e) {
    throw _handleError(e, 'Impossible de charger le groupe');
  }
}

  @override
  Future<Lot> getGroupeSuivant(int rotationId) async {
  try {
    // #209 — POST : « Groupe suivant » est l'ACTE d'avancer (ouvre le rang suivant côté
    // serveur + démarre son minuteur). Valider ne fait plus avancer.
    final response = await _apiClient.post(ApiConstants.groupeSuivant(rotationId));
    final data = (response.data as Map<String, dynamic>)['data'] as Map<String, dynamic>;
    return LotModel.fromJson(data);
  } on DioException catch (e) {
    throw _handleError(e, 'Aucun groupe suivant');
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

  // ── #307 — envoi PUR, utilisé UNIQUEMENT par SyncService ─────────────────
  //
  // Aucun repli local, aucune erreur avalée : si l'envoi n'aboutit pas, on
  // lève. C'est ce qui permet à la synchronisation de distinguer « c'est
  // parti » de « je n'ai pas pu », et donc de ne supprimer que ce que le
  // serveur a confirmé.
  //
  // Rejouable sans risque : le backend retrouve le NotationItem existant et le
  // met à jour (EvaluateurDashboardService.saisirNotation) — pas de doublon.
  @override
  Future<void> pushNotation(Notation notation) async {
    if (notation.stationId == null || notation.grilleId == null) {
      throw ArgumentError(
        'stationId et grilleId sont requis pour envoyer une notation',
      );
    }
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

  @override
  Future<void> validerGroupe(int rotationId) async {
  try {
    await _apiClient.post(ApiConstants.validerGroupe(rotationId));
  } on DioException catch (e) {
    throw _handleError(e, 'Impossible de valider le groupe');
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
        // #297 — un refus de verrouillage (400) porte son message métier dans
        // le corps JSON (ApiResponse.message), jamais dans e.message (générique
        // Dio, du type "Http status error [400]"). Sans cette extraction, le
        // refus détaillé du backend ("il reste 3 critères non notés pour...")
        // n'atteignait jamais l'écran.
        return Exception(_extractErrorMessage(e) ?? '$message : ${e.message}');
    }
  }

  // Même helper que AuthRepositoryImpl — un seul contrat d'enveloppe d'erreur.
  String? _extractErrorMessage(DioException e) {
    final data = e.response?.data;
    if (data is Map<String, dynamic> && data['message'] is String) {
      return data['message'] as String;
    }
    return null;
  }
}