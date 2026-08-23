// lib/features/profile/data/settings_store.dart
// ================================================
// W4 (S39) — LES RÉGLAGES SURVIVENT ENFIN AU LANCEMENT.
//
// Avant : ProfileBloc naissait sur `ProfileSettings()` et aucun gestionnaire
// n'écrivait nulle part — un évaluateur arabophone re-choisissait sa langue
// chaque matin, le thème sombre s'oubliait à chaque démarrage.
//
// SharedPreferences et PAS le stockage sécurisé : la langue et le thème ne
// sont pas des secrets, et surtout `TokenStore.deleteAll()` (déconnexion,
// échec de refresh) efface TOUT le coffre — les réglages doivent survivre à
// une déconnexion, pas mourir avec les jetons.

import 'package:shared_preferences/shared_preferences.dart';

import '../domain/entities/profile_settings.dart';

class SettingsStore {
  static const _kLanguage = 'settings.language';
  static const _kTheme    = 'settings.theme';

  final SharedPreferences _prefs;

  SettingsStore(this._prefs);

  static Future<SettingsStore> open() async =>
      SettingsStore(await SharedPreferences.getInstance());

  ProfileSettings load() {
    final lang = _prefs.getString(_kLanguage);
    final theme = _prefs.getString(_kTheme);
    return ProfileSettings(
      language: AppLanguage.values.firstWhere(
        (l) => l.code == lang,
        orElse: () => AppLanguage.french,
      ),
      themeMode: theme == 'dark' ? AppThemeMode.dark : AppThemeMode.light,
    );
  }

  Future<void> save(ProfileSettings settings) async {
    await _prefs.setString(_kLanguage, settings.language.code);
    await _prefs.setString(
        _kTheme, settings.themeMode == AppThemeMode.dark ? 'dark' : 'light');
  }
}
