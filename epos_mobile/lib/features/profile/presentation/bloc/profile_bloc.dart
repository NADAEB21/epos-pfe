// lib/features/profile/presentation/bloc/profile_bloc.dart
// ================================================
// Gère les paramètres du profil utilisateur :
//   • Langue (fr / en / ar)
//   • Notifications (activées / désactivées)
//   • Thème (clair / sombre)
//   • Changement de mot de passe
//   • Déconnexion

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

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

class ProfileNotificationsToggled extends ProfileEvent {
  final bool enabled;
  const ProfileNotificationsToggled(this.enabled);
  @override
  List<Object?> get props => [enabled];
}

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
  ProfileBloc() : super(const ProfileLoaded(ProfileSettings())) {
    on<ProfileLanguageChanged>(_onLanguageChanged);
    on<ProfileNotificationsToggled>(_onNotificationsToggled);
    on<ProfileThemeChanged>(_onThemeChanged);
    on<ProfilePasswordChangeRequested>(_onPasswordChangeRequested);
    on<ProfilePasswordChangeDismissed>(_onPasswordChangeDismissed);
  }

  void _onLanguageChanged(
    ProfileLanguageChanged event,
    Emitter<ProfileState> emit,
  ) {
    emit(ProfileLoaded(state.settings.copyWith(language: event.language)));
  }

  void _onNotificationsToggled(
    ProfileNotificationsToggled event,
    Emitter<ProfileState> emit,
  ) {
    emit(ProfileLoaded(
      state.settings.copyWith(notificationsEnabled: event.enabled),
    ));
  }

  void _onThemeChanged(
    ProfileThemeChanged event,
    Emitter<ProfileState> emit,
  ) {
    emit(ProfileLoaded(state.settings.copyWith(themeMode: event.themeMode)));
  }

  Future<void> _onPasswordChangeRequested(
    ProfilePasswordChangeRequested event,
    Emitter<ProfileState> emit,
  ) async {
    emit(ProfilePasswordChanging(state.settings));
    // Simulation d'un appel backend (mock)
    await Future.delayed(const Duration(milliseconds: 800));

    // Validation simple : mot de passe actuel == "password123" (mock)
    if (event.currentPassword == 'password123') {
      if (event.newPassword.length >= 8) {
        emit(ProfilePasswordChanged(state.settings));
      } else {
        emit(ProfilePasswordError(
          state.settings,
          'Le mot de passe doit contenir au moins 8 caractères.',
        ));
      }
    } else {
      emit(ProfilePasswordError(
        state.settings,
        'Mot de passe actuel incorrect.',
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