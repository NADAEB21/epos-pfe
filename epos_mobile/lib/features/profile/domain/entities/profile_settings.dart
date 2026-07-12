// lib/features/profile/domain/entities/profile_settings.dart
import 'dart:ui';
import 'package:equatable/equatable.dart';

enum AppLanguage { french, english, arabic }
enum AppThemeMode { light, dark }

extension AppLanguageExtension on AppLanguage {
  String get label {
    switch (this) {
      case AppLanguage.french:  return 'Français';
      case AppLanguage.english: return 'English';
      case AppLanguage.arabic:  return 'العربية';
    }
  }

  String get code {
    switch (this) {
      case AppLanguage.french:  return 'fr';
      case AppLanguage.english: return 'en';
      case AppLanguage.arabic:  return 'ar';
    }
  }

  Locale get locale {
    switch (this) {
      case AppLanguage.french:  return const Locale('fr');
      case AppLanguage.english: return const Locale('en');
      case AppLanguage.arabic:  return const Locale('ar');
    }
  }
}

extension AppThemeModeExtension on AppThemeMode {
  String get label {
    switch (this) {
      case AppThemeMode.light: return 'Clair (défaut)';
      case AppThemeMode.dark:  return 'Sombre';
    }
  }
}

class ProfileSettings extends Equatable {
  final AppLanguage  language;
  final bool         notificationsEnabled;
  final AppThemeMode themeMode;

  const ProfileSettings({
    this.language             = AppLanguage.french,
    this.notificationsEnabled = true,
    this.themeMode            = AppThemeMode.light,
  });

  ProfileSettings copyWith({
    AppLanguage?  language,
    bool?         notificationsEnabled,
    AppThemeMode? themeMode,
  }) {
    return ProfileSettings(
      language:             language             ?? this.language,
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
      themeMode:            themeMode            ?? this.themeMode,
    );
  }

  @override
  List<Object?> get props => [language, notificationsEnabled, themeMode];
}