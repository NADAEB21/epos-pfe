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

  /// BF1.3 — Demande de réinitialisation de mot de passe (utilisateur non
  /// connecté qui a oublié son mot de passe). Un email contenant un token à
  /// usage unique (valide 30 min) est envoyé si l'adresse est enregistrée.
  /// Ne lance jamais d'exception pour une adresse inconnue (anti-énumération) —
  /// seules les erreurs réseau/serveur sont propagées.
  Future<void> requestPasswordReset({required String email});

  /// BF1.3 — Confirme la réinitialisation avec le token reçu par email et
  /// définit le nouveau mot de passe. Lance une exception si le token est
  /// invalide, déjà utilisé, ou expiré.
  Future<void> confirmPasswordReset({
    required String token,
    required String newPassword,
  });

  /// Changement de mot de passe pour un utilisateur déjà connecté (écran
  /// Profil). Nécessite le mot de passe actuel ; lance une exception s'il est
  /// incorrect ou si le nouveau mot de passe ne respecte pas la politique
  /// (min. 8 caractères, 1 majuscule, 1 chiffre).
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  });
}