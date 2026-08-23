// test/unit/auth_bloc_test.dart
// ================================================
// Tests unitaires de AuthBloc — TOUS les événements.
//
// SANS bloc_test (parké, cf. pubspec.yaml). On écoute bloc.stream et on
// collecte la séquence d'états après add(), avec un court settle().
//
// _getAccessToken n'est volontairement PAS fourni : en le laissant à null,
// _startWebSocket() est un no-op garanti (voir auth_bloc.dart), ce qui
// isole ces tests de WebSocketService/singletons réseau — cohérent avec
// l'esprit "aucun réseau" du reste de la suite unitaire.

import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/auth/domain/entities/user.dart';
import 'package:epos_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:epos_mobile/features/auth/presentation/bloc/auth_bloc.dart';

// ── Doublure de repository ──────────────────────────────────────────────────
class _FakeAuthRepository implements AuthRepository {
  bool isAuth = false;
  User? currentUser;
  bool throwOnIsAuthenticated = false;
  bool logoutCalled = false;

  Future<User> Function(String email, String password)? onLogin;
  Future<void> Function(String email)? onRequestReset;
  Future<void> Function(String token, String newPassword)? onConfirmReset;

  @override
  Future<bool> isAuthenticated() async {
    if (throwOnIsAuthenticated) throw Exception('stockage indisponible');
    return isAuth;
  }

  @override
  Future<User?> getCurrentUser() async => currentUser;

  @override
  Future<User> login({required String email, required String password}) {
    if (onLogin != null) return onLogin!(email, password);
    throw UnimplementedError();
  }

  @override
  Future<void> logout() async {
    logoutCalled = true;
  }

  @override
  Future<void> requestPasswordReset({required String email}) async {
    if (onRequestReset != null) await onRequestReset!(email);
  }

  @override
  Future<void> confirmPasswordReset({
    required String token,
    required String newPassword,
  }) async {
    if (onConfirmReset != null) await onConfirmReset!(token, newPassword);
  }

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    throw UnimplementedError();
  }
}

const _user = User(
  id: 1, email: 'eval@epos.tn', nom: 'Hfaiedh', prenom: 'Firas',
  role: UserRole.evaluateur,
);

Future<List<AuthState>> _collect(AuthBloc bloc, AuthEvent event) async {
  final states = <AuthState>[];
  final sub = bloc.stream.listen(states.add);
  bloc.add(event);
  await Future<void>.delayed(const Duration(milliseconds: 100));
  await sub.cancel();
  return states;
}

void main() {
  group('AuthCheckRequested', () {
    test('pas de token local → AuthUnauthenticated', () async {
      final repo = _FakeAuthRepository()..isAuth = false;
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(bloc, const AuthCheckRequested());
      expect(states, [isA<AuthLoading>(), isA<AuthUnauthenticated>()]);
      await bloc.close();
    });

    test('token valide + profil récupéré → AuthAuthenticated', () async {
      final repo = _FakeAuthRepository()
        ..isAuth = true
        ..currentUser = _user;
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(bloc, const AuthCheckRequested());
      expect(states, [isA<AuthLoading>(), isA<AuthAuthenticated>()]);
      expect((states[1] as AuthAuthenticated).user, _user);
      await bloc.close();
    });

    test('token présent mais getCurrentUser renvoie null → AuthUnauthenticated',
            () async {
          final repo = _FakeAuthRepository()
            ..isAuth = true
            ..currentUser = null;
          final bloc = AuthBloc(authRepository: repo);
          final states = await _collect(bloc, const AuthCheckRequested());
          expect(states, [isA<AuthLoading>(), isA<AuthUnauthenticated>()]);
          await bloc.close();
        });

    test('isAuthenticated lève → capturé, AuthUnauthenticated (jamais de crash)',
            () async {
          final repo = _FakeAuthRepository()..throwOnIsAuthenticated = true;
          final bloc = AuthBloc(authRepository: repo);
          final states = await _collect(bloc, const AuthCheckRequested());
          expect(states, [isA<AuthLoading>(), isA<AuthUnauthenticated>()]);
          await bloc.close();
        });
  });

  group('AuthLoginRequested', () {
    test('identifiants valides → AuthAuthenticated', () async {
      final repo = _FakeAuthRepository()..onLogin = (email, pwd) async => _user;
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(
        bloc,
        const AuthLoginRequested(email: 'eval@epos.tn', password: 'Test1234'),
      );
      expect(states, [isA<AuthLoading>(), isA<AuthAuthenticated>()]);
      expect((states[1] as AuthAuthenticated).user, _user);
      await bloc.close();
    });

    test('identifiants invalides → AuthFailure avec le message métier',
            () async {
          final repo = _FakeAuthRepository()
            ..onLogin = (email, pwd) async =>
            throw Exception('Email ou mot de passe incorrect.');
          final bloc = AuthBloc(authRepository: repo);
          final states = await _collect(
            bloc,
            const AuthLoginRequested(email: 'x@epos.tn', password: 'bad'),
          );
          expect(states, [isA<AuthLoading>(), isA<AuthFailure>()]);
          expect((states[1] as AuthFailure).message,
              'Email ou mot de passe incorrect.');
          await bloc.close();
        });

    test('email envoyé au repository est trimmé', () async {
      String? emailRecu;
      final repo = _FakeAuthRepository()
        ..onLogin = (email, pwd) async {
          emailRecu = email;
          return _user;
        };
      final bloc = AuthBloc(authRepository: repo);
      await _collect(
        bloc,
        const AuthLoginRequested(email: '  eval@epos.tn  ', password: 'Test1234'),
      );
      expect(emailRecu, 'eval@epos.tn');
      await bloc.close();
    });
  });

  group('AuthLogoutRequested', () {
    test('déconnecte et retombe sur AuthUnauthenticated', () async {
      final repo = _FakeAuthRepository();
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(bloc, const AuthLogoutRequested());
      expect(states, [isA<AuthLoading>(), isA<AuthUnauthenticated>()]);
      expect(repo.logoutCalled, isTrue);
      await bloc.close();
    });
  });

  group('AuthPasswordResetRequested (mot de passe oublié — étape 1)', () {
    test('succès → AuthPasswordResetEmailSent avec l\'email trimmé', () async {
      final repo = _FakeAuthRepository()..onRequestReset = (email) async {};
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(
        bloc,
        const AuthPasswordResetRequested(email: ' eval@epos.tn '),
      );
      expect(states, [isA<AuthLoading>(), isA<AuthPasswordResetEmailSent>()]);
      expect((states[1] as AuthPasswordResetEmailSent).email, 'eval@epos.tn');
      await bloc.close();
    });

    test('échec réseau → AuthFailure', () async {
      final repo = _FakeAuthRepository()
        ..onRequestReset = (email) async => throw Exception('Hors ligne.');
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(
        bloc,
        const AuthPasswordResetRequested(email: 'eval@epos.tn'),
      );
      expect(states, [isA<AuthLoading>(), isA<AuthFailure>()]);
      expect((states[1] as AuthFailure).message, 'Hors ligne.');
      await bloc.close();
    });
  });

  group('AuthPasswordResetConfirmRequested (mot de passe oublié — étape 2)', () {
    test('code valide → AuthPasswordResetSuccess', () async {
      final repo = _FakeAuthRepository()
        ..onConfirmReset = (token, newPwd) async {};
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(
        bloc,
        const AuthPasswordResetConfirmRequested(
          token: '123456', newPassword: 'NouveauPass1',
        ),
      );
      expect(states, [isA<AuthLoading>(), isA<AuthPasswordResetSuccess>()]);
      await bloc.close();
    });

    test('code invalide/expiré → AuthFailure', () async {
      final repo = _FakeAuthRepository()
        ..onConfirmReset = (token, newPwd) async =>
        throw Exception('Code invalide ou expiré. Veuillez refaire une demande.');
      final bloc = AuthBloc(authRepository: repo);
      final states = await _collect(
        bloc,
        const AuthPasswordResetConfirmRequested(
          token: '000000', newPassword: 'X',
        ),
      );
      expect(states, [isA<AuthLoading>(), isA<AuthFailure>()]);
      expect((states[1] as AuthFailure).message,
          contains('Code invalide ou expiré'));
      await bloc.close();
    });
  });

  group('état initial', () {
    test('AuthBloc démarre sur AuthInitial avant tout événement', () {
      final bloc = AuthBloc(authRepository: _FakeAuthRepository());
      expect(bloc.state, isA<AuthInitial>());
      bloc.close();
    });
  });
}