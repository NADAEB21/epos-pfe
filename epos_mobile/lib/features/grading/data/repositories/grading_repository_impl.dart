// lib/features/grading/data/repositories/grading_repository_impl.dart

import 'package:dio/dio.dart';

import '../../../../core/constants/api_constants.dart';
import '../../../../core/network/api_client.dart';
import '../../domain/entities/grille.dart';
import '../../domain/entities/lot.dart';
import '../../domain/entities/notation.dart';
import '../../domain/repositories/grading_repository.dart';
import '../models/grading_models.dart';

/// Implémentation réelle du GradingRepository.
///
/// Corrections des décalages E, G, H :
///   - getLot         → GET  /evaluateur/stations/{id}/lots/{num}
///   - saveNotation   → POST /evaluateur/notations/saisir
///   - validerEtudiant→ POST /evaluateur/etudiants/{id}/stations/{id}/valider
///   - validerLot     → POST /evaluateur/lots/{id}/valider
///
/// getGrille reste inchangé car l'endpoint /stations/{id}/grille
/// de l'exam-service est déjà correctement routé par la gateway.
class GradingRepositoryImpl implements GradingRepository {
  final ApiClient _apiClient;

  GradingRepositoryImpl({required ApiClient apiClient})
      : _apiClient = apiClient;

  // ── GET /stations/{id}/grille ─────────────────────────────────────────────
  /// Charge la grille d'évaluation depuis l'exam-service.
  ///
  /// La réponse est enveloppée dans ApiResponse<GrilleResponse> :
  /// { "success": true, "data": { "id": 1, "nom": "...", "noteMax": 20.0, "items": [...] } }
  @override
  Future<Grille> getGrille(int stationId) async {
    try {
      final response = await _apiClient.get(
        ApiConstants.stationGrille(stationId),
      );
      final body = response.data as Map<String, dynamic>;
      // Déballe l'enveloppe ApiResponse
      final data = body['data'] as Map<String, dynamic>;
      return GrilleModel.fromJson(data);
    } on DioException catch (e) {
      throw _handleError(e, 'Impossible de charger la grille');
    }
  }

  // ── GET /evaluateur/stations/{id}/lots/{num} ──────────────────────────────
  /// Charge le lot courant avec ses étudiants.
  ///
  /// Correction décalage E : l'ancien endpoint /stations/{id}/lots/{num}
  /// n'existait pas → remplacé par /evaluateur/stations/{id}/lots/{num}.
  ///
  /// Réponse JSON (LotDetailResponse) :
  /// { "success": true, "data": { "id": 12, "numero": 3, "total": 8, "valide": false, "etudiants": [...] } }
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

  // ── POST /evaluateur/notations/saisir ─────────────────────────────────────
  /// Sauvegarde une notation pour un critère d'un étudiant.
  ///
  /// Correction décalage G : l'ancien POST /notations envoyait
  /// {etudiantId, itemId, valeur} — structure incompatible avec le backend réel.
  /// Le nouveau endpoint /evaluateur/notations/saisir accepte
  /// {etudiantId, stationId, grilleId, itemId, valeur} et gère automatiquement
  /// la chaîne Notation → RotationAssignment → ExamenParticipation.
  ///
  /// stationId et grilleId sont portés par Notation (ajoutés dans notation.dart).
  /// En mode offline (pas de connexion), la saisie continue sans erreur.
  @override
  Future<void> saveNotation(Notation notation) async {
    // Ignore les notations sans contexte (stationId/grilleId null = mode mock)
    if (notation.stationId == null || notation.grilleId == null) return;

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
    } on DioException catch (e) {
      // Erreur réseau → on laisse l'état local intact (CA-4.3)
      if (e.type == DioExceptionType.connectionError ||
          e.type == DioExceptionType.connectionTimeout) {
        return;
      }
      throw _handleError(e, 'Erreur lors de la sauvegarde de la notation');
    }
  }

  // ── POST /evaluateur/notations/saisir (batch) ─────────────────────────────
  /// Synchronise plusieurs notations (mode offline → reconnexion).
  @override
  Future<void> saveNotations(List<Notation> notations) async {
    for (final n in notations) {
      await saveNotation(n);
    }
  }

  // ── POST /evaluateur/etudiants/{id}/stations/{id}/valider ─────────────────
  /// Verrouille les notes d'un étudiant pour une station.
  ///
  /// Correction décalage H : l'ancien endpoint /stations/{id}/etudiants/{id}/valider
  /// n'existait pas dans le backend réel.
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
  /// Valide tout le lot et le marque comme TERMINE.
  ///
  /// Correction décalage H : l'endpoint /lots/{id}/valider n'existait pas.
  @override
  Future<void> validerLot(int lotId) async {
    try {
      await _apiClient.post(ApiConstants.validerLot(lotId));
    } on DioException catch (e) {
      throw _handleError(e, 'Impossible de valider le lot');
    }
  }

  // ── Substitution (non bloquant — hors scope MVP) ──────────────────────────
  @override
  Future<Etudiant> substituerEtudiant({
    required int lotId,
    required int etudiantAbsentId,
    required int etudiantRemplacantId,
  }) async {
    // Fonctionnalité BF3.4 — à implémenter lors du sprint 7.
    // Retourne un étudiant fictif pour ne pas bloquer la compilation.
    throw UnimplementedError(
      'substituerEtudiant sera implémenté au sprint 7 (BF3.4).',
    );
  }

  // ── Offline sync (non bloquant — hors scope MVP) ──────────────────────────
  @override
  Future<List<Notation>> getNotationsNonSynchro() async => [];

  @override
  Future<void> marquerSynchro(List<int> notationIds) async {}

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