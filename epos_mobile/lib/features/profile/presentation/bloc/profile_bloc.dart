// lib/features/profile/presentation/bloc/profile_bloc.dart
// ================================================
// Gère les paramètres du profil utilisateur :
//   • Langue (fr / en / ar)
//   • Notifications (activées / désactivées)
//   • Thème (clair / sombre)
//   • Changement de mot de passe — BRANCHÉ SUR LE VRAI BACKEND
//     (PUT /api/v1/auth/change-password), remplace l'ancienne vérification
//     mock 'password123'. Voir AuthRepository.changePassword().
//   • Déconnexion

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../auth/domain/repositories/auth_repository.dart';
import '../../data/settings_store.dart';
import '../../domain/entities/profile_settings.dart';

// ========================
// EVENTS
// ========================
abstract class ProfileEvent extends Equatable {
  const ProfileEvent();
  @override
  List<Object?> get props => [];
}

class ProfileLanguageChanged extends ProfileEvent {
  final AppLanguage language;
  const ProfileLanguageChanged(this.language);
  @override
  List<Object?> get props => [language];
}

// W4 — ProfileNotificationsToggled supprimé avec l'interrupteur qu'il servait :
// il ne pilotait aucun canal de notification (aucun consommateur du booléen).

class ProfileThemeChanged extends ProfileEvent {
  final AppThemeMode themeMode;
  const ProfileThemeChanged(this.themeMode);
  @override
  List<Object?> get props => [themeMode];
}

class ProfilePasswordChangeRequested extends ProfileEvent {
  final String currentPassword;
  final String newPassword;
  const ProfilePasswordChangeRequested({
    required this.currentPassword,
    required this.newPassword,
  });
  @override
  List<Object?> get props => [currentPassword, newPassword];
}

class ProfilePasswordChangeDismissed extends ProfileEvent {
  const ProfilePasswordChangeDismissed();
}

// ========================
// STATES
// ========================
abstract class ProfileState extends Equatable {
  final ProfileSettings settings;
  const ProfileState(this.settings);
  @override
  List<Object?> get props => [settings];
}

class ProfileInitial extends ProfileState {
  const ProfileInitial() : super(const ProfileSettings());
}

class ProfileLoaded extends ProfileState {
  const ProfileLoaded(super.settings);
}

class ProfilePasswordChanging extends ProfileState {
  const ProfilePasswordChanging(super.settings);
}

class ProfilePasswordChanged extends ProfileState {
  const ProfilePasswordChanged(super.settings);
}

class ProfilePasswordError extends ProfileState {
  final String message;
  const ProfilePasswordError(super.settings, this.message);
  @override
  List<Object?> get props => [settings, message];
}

// ========================
// BLOC
// ========================
class ProfileBloc extends Bloc<ProfileEvent, ProfileState> {
  final AuthRepository _authRepository;

  /// W4 — persistance des réglages. Nullable : les tests et le mode mock
  /// peuvent s'en passer ; sans store, le comportement redevient « mémoire ».
  final SettingsStore? _settingsStore;

  ProfileBloc({
    required AuthRepository authRepository,
    SettingsStore? settingsStore,
    ProfileSettings? initialSettings,
  })  : _authRepository = authRepository,
        _settingsStore = settingsStore,
        super(ProfileLoaded(initialSettings ?? const ProfileSettings())) {
    on<ProfileLanguageChanged>(_onLanguageChanged);
    on<ProfileThemeChanged>(_onThemeChanged);
    on<ProfilePasswordChangeRequested>(_onPasswordChangeRequested);
    on<ProfilePasswordChangeDismissed>(_onPasswordChangeDismissed);
  }

  /// W4 — chaque changement s'écrit immédiatement (meilleur effort : un échec
  /// d'écriture ne casse jamais le changement à l'écran).
  void _persist(ProfileSettings settings) {
    _settingsStore?.save(settings).catchError((_) {});
  }

  void _onLanguageChanged(
      ProfileLanguageChanged event,
      Emitter<ProfileState> emit,
      ) {
    final next = state.settings.copyWith(language: event.language);
    _persist(next);
    emit(ProfileLoaded(next));
  }

  void _onThemeChanged(
      ProfileThemeChanged event,
      Emitter<ProfileState> emit,
      ) {
    final next = state.settings.copyWith(themeMode: event.themeMode);
    _persist(next);
    emit(ProfileLoaded(next));
  }

  /// Appelle le vrai backend (PUT /auth/change-password) via AuthRepository.
  /// Remplace l'ancienne validation mock ('password123' codé en dur) : le
  /// mot de passe actuel est désormais vérifié côté serveur, et le nouveau
  /// mot de passe doit respecter la politique (min. 8, 1 maj., 1 chiffre)
  /// — sinon le backend renvoie une erreur 400 explicite.
  Future<void> _onPasswordChangeRequested(
      ProfilePasswordChangeRequested event,
      Emitter<ProfileState> emit,
      ) async {
    emit(ProfilePasswordChanging(state.settings));
    try {
      await _authRepository.changePassword(
        currentPassword: event.currentPassword,
        newPassword:     event.newPassword,
      );
      emit(ProfilePasswordChanged(state.settings));
    } catch (e) {
      emit(ProfilePasswordError(
        state.settings,
        e.toString().replaceFirst('Exception: ', ''),
      ));
    }
  }

  void _onPasswordChangeDismissed(
      ProfilePasswordChangeDismissed event,
      Emitter<ProfileState> emit,
      ) {
    emit(ProfileLoaded(state.settings));
  }
}