// lib/features/auth/domain/repositories/auth_repository.dart

import '../../domain/entities/user.dart';

abstract class AuthRepository {
  /// Connexion avec email + mot de passe.
  /// Retourne l'utilisateur connecté ou lance une exception.
  Future<User> login({
    required String email,
    required String password,
  });

  /// Déconnexion : supprime les tokens locaux.
  Future<void> logout();

  /// Vérifie si un token valide existe en local.
  Future<bool> isAuthenticated();

  /// Récupère l'utilisateur courant depuis le token stocké.
  Future<User?> getCurrentUser();
}