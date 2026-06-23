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
// USAGE : dans main.dart, remplacer AuthRepositoryImpl par MockAuthRepository.
// ================================================

import '../../domain/entities/user.dart';
import '../../domain/repositories/auth_repository.dart';

class MockAuthRepository implements AuthRepository {
  // Simule un délai réseau réaliste
  static const _delay = Duration(milliseconds: 1200);

  // Utilisateur connecté en mémoire
  User? _currentUser;

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

    final account = _fakeUsers[email.trim().toLowerCase()];

    // Compte verrouillé
    if (account != null && account.isLocked) {
      throw Exception(
        'Compte verrouillé après 3 tentatives. Réessayez dans 15 minutes.',
      );
    }

    // Identifiants incorrects
    if (account == null || account.password != password) {
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