// lib/features/auth/presentation/bloc/auth_bloc.dart
// ================================================
// BF6.1 — WebSocket : démarrage au login, arrêt propre au logout.
//          Le token JWT est passé au WebSocketService pour que la
//          connexion STOMP soit authentifiée.
// BF6.2 — Offline  : arrêt propre du ConnectivityService au logout
//          pour éviter les polls réseau inutiles.

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/offline/connectivity_service.dart';
import '../../../../core/offline/offline_storage_service.dart';
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

  // ── Helpers privés ────────────────────────────────────────────────────────

  /// BF6.1 — Récupère le token et initialise le WebSocketService.
  /// En mode mock (_getAccessToken == null) : no-op silencieux.
  Future<void> _startWebSocket() async {
    if (_getAccessToken == null) return;
    try {
      final token = await _getAccessToken!();
      if (token != null && token.isNotEmpty) {
        WebSocketService.instance.init(token);
      }
    } catch (e) {
      // Le WebSocket est optionnel (BF6.1 = "Should Have").
      // Un échec ici ne doit jamais bloquer le flux d'authentification.
      debugPrint('[AuthBloc] WebSocket init ignoré : $e');
    }
  }
}