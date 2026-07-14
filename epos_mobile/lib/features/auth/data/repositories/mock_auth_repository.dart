// lib/features/auth/data/repositories/mock_auth_repository.dart
// ================================================
// Fausse implémentation du AuthRepository pour tester
// l'UI sans backend.
//
// Comptes de test disponibles :
//   evaluateur@epos.tn  / Test1234  → rôle Évaluateur
//   responsable@epos.tn / Test1234  → rôle Responsable
//   admin@epos.tn       / Test1234  → rôle Administrateur
//   (n'importe quel autre email)    → erreur 401
//   locked@epos.tn      / Test1234  → erreur compte verrouillé
//
// Mot de passe oublié (mock) : un code fixe "123456" simule le token reçu
// par email — il est affiché dans la console (debugPrint) pour les tests
// manuels, comme le ferait StubEmailService côté backend réel.
//
// USAGE : dans main.dart, remplacer AuthRepositoryImpl par MockAuthRepository.
// ================================================

import 'package:flutter/foundation.dart';

import '../../domain/entities/user.dart';
import '../../domain/repositories/auth_repository.dart';

class MockAuthRepository implements AuthRepository {
  // Simule un délai réseau réaliste
  static const _delay = Duration(milliseconds: 1200);

  // Code de réinitialisation simulé (équivalent du token envoyé par email).
  static const String _mockResetCode = '123456';

  // Utilisateur connecté en mémoire
  User? _currentUser;

  // Mots de passe modifiés en mémoire (email → nouveau mot de passe),
  // consultés en priorité sur le mot de passe fixe de _fakeUsers.
  final Map<String, String> _passwordOverrides = {};

  // Email en attente de confirmation de réinitialisation (étape 2 du flux).
  String? _pendingResetEmail;

  // Base de données fictive des comptes de test
  static const _fakeUsers = {
    'evaluateur@epos.tn': _FakeAccount(
      password: 'Test1234',
      user: User(
        id:     1,
        email:  'evaluateur@epos.tn',
        nom:    'Hfaiedh',
        prenom: 'Firas',
        role:   UserRole.evaluateur,
      ),
    ),
    'responsable@epos.tn': _FakeAccount(
      password: 'Test1234',
      user: User(
        id:     2,
        email:  'responsable@epos.tn',
        nom:    'Ben Ali',
        prenom: 'Sonia',
        role:   UserRole.responsable,
      ),
    ),
    'admin@epos.tn': _FakeAccount(
      password: 'Test1234',
      user: User(
        id:     3,
        email:  'admin@epos.tn',
        nom:    'Administrateur',
        prenom: 'EPOS',
        role:   UserRole.administrateur,
      ),
    ),
    'locked@epos.tn': _FakeAccount(
      password: 'Test1234',
      user: User(
        id:     4,
        email:  'locked@epos.tn',
        nom:    'Locked',
        prenom: 'User',
        role:   UserRole.evaluateur,
      ),
      isLocked: true,
    ),
  };

  @override
  Future<User> login({
    required String email,
    required String password,
  }) async {
    // Simule la latence réseau
    await Future.delayed(_delay);

    final normalizedEmail = email.trim().toLowerCase();
    final account = _fakeUsers[normalizedEmail];

    // Compte verrouillé
    if (account != null && account.isLocked) {
      throw Exception(
        'Compte verrouillé après 3 tentatives. Réessayez dans 15 minutes.',
      );
    }

    // Le mot de passe effectif est celui modifié en mémoire (changement de
    // mot de passe ou réinitialisation), sinon le mot de passe par défaut.
    final effectivePassword =
        _passwordOverrides[normalizedEmail] ?? account?.password;

    // Identifiants incorrects
    if (account == null || effectivePassword != password) {
      throw Exception('Email ou mot de passe incorrect.');
    }

    _currentUser = account.user;
    return account.user;
  }

  @override
  Future<void> logout() async {
    await Future.delayed(const Duration(milliseconds: 300));
    _currentUser = null;
  }

  @override
  Future<bool> isAuthenticated() async {
    // En mode mock : pas de token persisté → toujours déconnecté au démarrage
    return false;
  }

  @override
  Future<User?> getCurrentUser() async {
    return _currentUser;
  }

  // ── BF1.3 — Mot de passe oublié (mock) ──────────────────────────────────

  @override
  Future<void> requestPasswordReset({required String email}) async {
    await Future.delayed(_delay);

    final normalizedEmail = email.trim().toLowerCase();
    if (_fakeUsers.containsKey(normalizedEmail)) {
      _pendingResetEmail = normalizedEmail;
      debugPrint(
        '📧 [MOCK] "Email" de réinitialisation envoyé à $email — '
            'code : $_mockResetCode',
      );
    }
    // Toujours un succès silencieux, même si l'adresse est inconnue
    // (comportement identique au backend réel : anti-énumération).
  }

  @override
  Future<void> confirmPasswordReset({
    required String token,
    required String newPassword,
  }) async {
    await Future.delayed(_delay);

    if (_pendingResetEmail == null || token.trim() != _mockResetCode) {
      throw Exception('Code invalide ou expiré. Veuillez refaire une demande.');
    }

    _passwordOverrides[_pendingResetEmail!] = newPassword;
    _pendingResetEmail = null;
  }

  // ── Changement de mot de passe (mock) ───────────────────────────────────

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    await Future.delayed(_delay);

    if (_currentUser == null) {
      throw Exception('Aucun utilisateur connecté.');
    }

    final email = _currentUser!.email.toLowerCase();
    final account = _fakeUsers[email];
    final effectivePassword = _passwordOverrides[email] ?? account?.password;

    if (account == null || effectivePassword != currentPassword) {
      throw Exception('Mot de passe actuel incorrect.');
    }
    if (newPassword.length < 8) {
      throw Exception('Le mot de passe doit contenir au moins 8 caractères.');
    }

    _passwordOverrides[email] = newPassword;
  }
}

// Classe utilitaire interne
class _FakeAccount {
  final String password;
  final User   user;
  final bool   isLocked;

  const _FakeAccount({
    required this.password,
    required this.user,
    this.isLocked = false,
  });
}