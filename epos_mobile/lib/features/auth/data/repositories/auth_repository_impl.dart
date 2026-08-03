// lib/features/auth/data/repositories/auth_repository_impl.dart

import 'package:dio/dio.dart';

import '../../../../core/constants/api_constants.dart';
import '../../../../core/network/api_client.dart';
import '../../domain/entities/user.dart';
import '../../domain/repositories/auth_repository.dart';
import '../models/user_model.dart';

class AuthRepositoryImpl implements AuthRepository {
  final ApiClient _apiClient;

  const AuthRepositoryImpl({required ApiClient apiClient})
      : _apiClient = apiClient;

  /// Connexion en deux étapes :
  ///   1. POST /auth/login  → { "success": true, "data": { "accessToken": "...", "refreshToken": "..." } }
  ///   2. GET  /auth/me     → { "success": true, "data": { "id": 3, "email": "...", "nom": "...", ... } }
  @override
  Future<User> login({
    required String email,
    required String password,
  }) async {
    try {
      // Étape 1 : authentification
      final loginResponse = await _apiClient.post(
        ApiConstants.login,
        data: {'email': email, 'password': password},
      );

      // Correction point 2 : déballe l'enveloppe ApiResponse<LoginResponse>
      // Structure backend : { "success": true, "data": { "accessToken": "...", "refreshToken": "..." } }
      final body   = loginResponse.data as Map<String, dynamic>;
      final tokens = body['data']        as Map<String, dynamic>;

      await _apiClient.saveTokens(
        accessToken:  tokens['accessToken']  as String,
        refreshToken: tokens['refreshToken'] as String,
      );

      // Étape 2 : récupère le profil utilisateur avec le token sauvegardé
      final meResponse = await _apiClient.get(ApiConstants.me);
      final meBody     = meResponse.data as Map<String, dynamic>;
      // Structure backend : { "success": true, "data": { "id": 3, ... } }
      final userData   = meBody['data'] as Map<String, dynamic>;

      return UserModel.fromJson(userData);

    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        throw Exception('Email ou mot de passe incorrect.');
      } else if (e.response?.statusCode == 403) {
        // #294 — le backend renvoie 403 (AccountLockedException), pas 423 : ce
        // cas ne se déclenchait donc JAMAIS, et l'évaluateur verrouillé le matin
        // de l'épreuve recevait une erreur Dio brute. Le serveur distingue
        // désormais « désactivé » de « verrouillé N minutes » et annonce le
        // délai — on affiche SON message plutôt qu'un délai inventé (l'ancien
        // texte promettait 15 minutes, alors que le verrou était définitif).
        throw Exception(_extractErrorMessage(e) ??
            "Connexion refusée. Contactez l'administration de la faculté.");
      } else if (e.type == DioExceptionType.connectionTimeout ||
          e.type == DioExceptionType.receiveTimeout) {
        throw Exception(
          'Impossible de joindre le serveur. Vérifiez votre connexion.',
        );
      }
      throw Exception('Erreur de connexion : ${e.message}');
    }
  }

  @override
  Future<void> logout() async {
    try {
      await _apiClient.post(ApiConstants.logout);
    } catch (_) {
      // On ignore les erreurs réseau au logout
    } finally {
      await _apiClient.clearTokens();
    }
  }

  @override
  Future<bool> isAuthenticated() async {
    final token = await _apiClient.getAccessToken();
    return token != null && token.isNotEmpty;
  }

  /// Récupère l'utilisateur depuis /auth/me au démarrage si un token local existe.
  @override
  Future<User?> getCurrentUser() async {
    try {
      final response = await _apiClient.get(ApiConstants.me);
      final body     = response.data as Map<String, dynamic>;
      final userData = body['data']  as Map<String, dynamic>;
      return UserModel.fromJson(userData);
    } catch (_) {
      return null;
    }
  }

  // ── BF1.3 — Mot de passe oublié ─────────────────────────────────────────

  @override
  Future<void> requestPasswordReset({required String email}) async {
    try {
      await _apiClient.post(
        ApiConstants.passwordResetRequest,
        data: {'email': email},
      );
      // Le backend répond toujours 200 (que l'email existe ou non) pour ne
      // jamais révéler si une adresse est enregistrée.
    } on DioException catch (e) {
      throw Exception(_extractErrorMessage(e) ??
          "Impossible d'envoyer la demande. Vérifiez votre connexion.");
    }
  }

  @override
  Future<void> confirmPasswordReset({
    required String token,
    required String newPassword,
  }) async {
    try {
      await _apiClient.post(
        ApiConstants.passwordResetConfirm,
        data: {
          'token': token,
          'newPassword': newPassword,
        },
      );
    } on DioException catch (e) {
      if (e.response?.statusCode == 400) {
        throw Exception(_extractErrorMessage(e) ??
            'Code invalide ou expiré. Veuillez refaire une demande.');
      }
      throw Exception(_extractErrorMessage(e) ??
          'Impossible de réinitialiser le mot de passe.');
    }
  }

  // ── Changement de mot de passe (utilisateur connecté — écran Profil) ────

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    try {
      await _apiClient.put(
        ApiConstants.changePassword,
        data: {
          'currentPassword': currentPassword,
          'newPassword': newPassword,
        },
      );
    } on DioException catch (e) {
      if (e.response?.statusCode == 401) {
        throw Exception('Mot de passe actuel incorrect.');
      }
      if (e.response?.statusCode == 400) {
        throw Exception(_extractErrorMessage(e) ??
            'Le nouveau mot de passe ne respecte pas les critères requis '
                '(min. 8 caractères, 1 majuscule, 1 chiffre).');
      }
      throw Exception('Impossible de modifier le mot de passe : ${e.message}');
    }
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  /// Extrait le champ "message" de l'enveloppe ApiResponse d'erreur du
  /// backend ({ "success": false, "message": "..." }), quand présent.
  String? _extractErrorMessage(DioException e) {
    final data = e.response?.data;
    if (data is Map<String, dynamic> && data['message'] is String) {
      return data['message'] as String;
    }
    return null;
  }
}