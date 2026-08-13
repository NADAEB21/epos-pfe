// lib/features/auth/presentation/bloc/auth_bloc.dart
// ================================================
// BF6.1 — WebSocket : démarrage au login, arrêt propre au logout.
//          Le token JWT est passé au WebSocketService pour que la
//          connexion STOMP soit authentifiée.
// BF6.2 — Offline  : arrêt propre du ConnectivityService au logout
//          pour éviter les polls réseau inutiles.
// BF1.3 — Mot de passe oublié : events/states dédiés (AuthPasswordReset*)
//          consommés par ForgotPasswordScreen. Réutilise ce bloc plutôt
//          que d'en créer un second, car AuthRepository est déjà injecté
//          ici et ces états n'affectent pas la navigation racine (voir
//          app.dart : le listener n'agit que sur Authenticated/
//          Unauthenticated/Failure).

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/offline/websocket_service.dart';
import '../../domain/entities/user.dart';
import '../../domain/repositories/auth_repository.dart';
import 'package:flutter/foundation.dart';

// ════════════════════════════════════════════════
// EVENTS
// ════════════════════════════════════════════════
abstract class AuthEvent extends Equatable {
  const AuthEvent();
  @override
  List<Object?> get props => [];
}

/// Vérification au démarrage de l'app (token existant ?)
class AuthCheckRequested extends AuthEvent {
  const AuthCheckRequested();
}

/// L'utilisateur soumet le formulaire de login
class AuthLoginRequested extends AuthEvent {
  final String email;
  final String password;

  const AuthLoginRequested({
    required this.email,
    required this.password,
  });

  @override
  List<Object?> get props => [email, password];
}

/// L'utilisateur clique sur "Se déconnecter"
class AuthLogoutRequested extends AuthEvent {
  const AuthLogoutRequested();
}

/// BF1.3 — Étape 1 du "mot de passe oublié" : l'utilisateur soumet son email.
class AuthPasswordResetRequested extends AuthEvent {
  final String email;

  const AuthPasswordResetRequested({required this.email});

  @override
  List<Object?> get props => [email];
}

/// BF1.3 — Étape 2 : l'utilisateur soumet le code/token reçu par email et
/// son nouveau mot de passe.
class AuthPasswordResetConfirmRequested extends AuthEvent {
  final String token;
  final String newPassword;

  const AuthPasswordResetConfirmRequested({
    required this.token,
    required this.newPassword,
  });

  @override
  List<Object?> get props => [token, newPassword];
}

// ════════════════════════════════════════════════
// STATES
// ════════════════════════════════════════════════
abstract class AuthState extends Equatable {
  const AuthState();
  @override
  List<Object?> get props => [];
}

/// État initial — vérification en cours
class AuthInitial extends AuthState {}

/// Chargement (login en cours, vérification token…)
class AuthLoading extends AuthState {}

/// Authentifié avec succès
class AuthAuthenticated extends AuthState {
  final User user;
  const AuthAuthenticated(this.user);
  @override
  List<Object?> get props => [user];
}

/// Non authentifié (pas de token, ou déconnecté)
class AuthUnauthenticated extends AuthState {}

/// Erreur d'authentification
class AuthFailure extends AuthState {
  final String message;
  const AuthFailure(this.message);
  @override
  List<Object?> get props => [message];
}

/// BF1.3 — La demande de réinitialisation a été envoyée avec succès.
class AuthPasswordResetEmailSent extends AuthState {
  final String email;
  const AuthPasswordResetEmailSent(this.email);
  @override
  List<Object?> get props => [email];
}

/// BF1.3 — Le mot de passe a été réinitialisé avec succès via le code reçu.
class AuthPasswordResetSuccess extends AuthState {
  const AuthPasswordResetSuccess();
}

// ════════════════════════════════════════════════
// BLOC
// ════════════════════════════════════════════════
class AuthBloc extends Bloc<AuthEvent, AuthState> {
  final AuthRepository _authRepository;

  /// BF6.1 — Référence optionnelle à l'ApiClient pour récupérer
  /// le token d'accès après login et initialiser le WebSocketService.
  /// Null en mode mock (WebSocket non utilisé).
  final Future<String?> Function()? _getAccessToken;

  AuthBloc({
    required AuthRepository authRepository,
    Future<String?> Function()? getAccessToken,
  })  : _authRepository   = authRepository,
        _getAccessToken    = getAccessToken,
        super(AuthInitial()) {
    on<AuthCheckRequested> (_onCheckRequested);
    on<AuthLoginRequested> (_onLoginRequested);
    on<AuthLogoutRequested>(_onLogoutRequested);
    on<AuthPasswordResetRequested>       (_onPasswordResetRequested);
    on<AuthPasswordResetConfirmRequested>(_onPasswordResetConfirmRequested);
  }

  // ── Vérification au démarrage ─────────────────────────────────────────────
  Future<void> _onCheckRequested(
      AuthCheckRequested event,
      Emitter<AuthState> emit,
      ) async {
    emit(AuthLoading());
    try {
      final isAuth = await _authRepository.isAuthenticated();
      if (!isAuth) {
        emit(AuthUnauthenticated());
        return;
      }
      final user = await _authRepository.getCurrentUser();
      if (user != null) {
        // BF6.1 — Reprend la connexion WebSocket si un token local existe
        await _startWebSocket();
        emit(AuthAuthenticated(user));
      } else {
        emit(AuthUnauthenticated());
      }
    } catch (_) {
      emit(AuthUnauthenticated());
    }
  }

  // ── Login ─────────────────────────────────────────────────────────────────
  Future<void> _onLoginRequested(
      AuthLoginRequested event,
      Emitter<AuthState> emit,
      ) async {
    emit(AuthLoading());
    try {
      final user = await _authRepository.login(
        email:    event.email.trim(),
        password: event.password,
      );

      // BF6.1 — Démarre le WebSocket après login réussi.
      // Le token vient d'être écrit par AuthRepositoryImpl dans le secure storage.
      await _startWebSocket();

      emit(AuthAuthenticated(user));
    } catch (e) {
      emit(AuthFailure(e.toString().replaceFirst('Exception: ', '')));
    }
  }

  // ── Logout ────────────────────────────────────────────────────────────────
  Future<void> _onLogoutRequested(
      AuthLogoutRequested event,
      Emitter<AuthState> emit,
      ) async {
    emit(AuthLoading());

    // BF6.1 — Arrêt propre du WebSocket avant la révocation du token.
    // L'ordre est important : le serveur révoque le token immédiatement,
    // donc on coupe la connexion STOMP avant que le handshake échoue.
    WebSocketService.instance.stop();

    // BF6.2 — On ne vide PAS la base SQLite locale au logout.
    // Les notations hors-ligne doivent survivre à une reconnexion pour
    // permettre la synchronisation lors du prochain login.
    // OfflineStorageService.instance.clearAll() n'est PAS appelé ici.

    await _authRepository.logout();
    emit(AuthUnauthenticated());
  }

  // ── BF1.3 — Mot de passe oublié : étape 1 (demande) ─────────────────────
  Future<void> _onPasswordResetRequested(
      AuthPasswordResetRequested event,
      Emitter<AuthState> emit,
      ) async {
    emit(AuthLoading());
    try {
      await _authRepository.requestPasswordReset(email: event.email.trim());
      emit(AuthPasswordResetEmailSent(event.email.trim()));
    } catch (e) {
      emit(AuthFailure(e.toString().replaceFirst('Exception: ', '')));
    }
  }

  // ── BF1.3 — Mot de passe oublié : étape 2 (confirmation) ────────────────
  Future<void> _onPasswordResetConfirmRequested(
      AuthPasswordResetConfirmRequested event,
      Emitter<AuthState> emit,
      ) async {
    emit(AuthLoading());
    try {
      await _authRepository.confirmPasswordReset(
        token:       event.token.trim(),
        newPassword: event.newPassword,
      );
      emit(const AuthPasswordResetSuccess());
    } catch (e) {
      emit(AuthFailure(e.toString().replaceFirst('Exception: ', '')));
    }
  }

  // ── Helpers privés ────────────────────────────────────────────────────────

  /// BF6.1 + #306 — Initialise le WebSocketService avec le FOURNISSEUR de
  /// jeton, pas un jeton figé : la connexion STOMP vit des heures et doit se
  /// reconnecter avec un jeton frais après l'expiration de celui du login
  /// (TTL 4 h). En mode mock (_getAccessToken == null) : no-op silencieux.
  Future<void> _startWebSocket() async {
    final provider = _getAccessToken;
    if (provider == null) return;
    try {
      WebSocketService.instance.init(provider);
    } catch (e) {
      // Le WebSocket est optionnel (BF6.1 = "Should Have").
      // Un échec ici ne doit jamais bloquer le flux d'authentification.
      debugPrint('[AuthBloc] WebSocket init ignoré : $e');
    }
  }
}