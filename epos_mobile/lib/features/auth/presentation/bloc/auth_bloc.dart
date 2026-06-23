// lib/features/auth/presentation/bloc/auth_bloc.dart

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

import '../../domain/entities/user.dart';
import '../../domain/repositories/auth_repository.dart';

// ========================
// EVENTS
// ========================
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

// ========================
// STATES
// ========================
abstract class AuthState extends Equatable {
  const AuthState();
  @override
  List<Object?> get props => [];
}

/// État initial — vérification en cours
class AuthInitial extends AuthState {}

/// Chargement (login en cours, vérification token...)
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

// ========================
// BLOC
// ========================
class AuthBloc extends Bloc<AuthEvent, AuthState> {
  final AuthRepository _authRepository;

  AuthBloc({required AuthRepository authRepository})
      : _authRepository = authRepository,
        super(AuthInitial()) {
    on<AuthCheckRequested> (_onCheckRequested);
    on<AuthLoginRequested> (_onLoginRequested);
    on<AuthLogoutRequested>(_onLogoutRequested);
  }

  /// Vérifie au démarrage si un token valide existe
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
        emit(AuthAuthenticated(user));
      } else {
        emit(AuthUnauthenticated());
      }
    } catch (_) {
      emit(AuthUnauthenticated());
    }
  }

  /// Gère la tentative de connexion
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
      emit(AuthAuthenticated(user));
    } catch (e) {
      emit(AuthFailure(e.toString().replaceFirst('Exception: ', '')));
    }
  }

  /// Gère la déconnexion
  Future<void> _onLogoutRequested(
    AuthLogoutRequested event,
    Emitter<AuthState> emit,
  ) async {
    emit(AuthLoading());
    await _authRepository.logout();
    emit(AuthUnauthenticated());
  }
}