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
      } else if (e.response?.statusCode == 423) {
        throw Exception(
          'Compte verrouillé après 3 tentatives. Réessayez dans 15 minutes.',
        );
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
}