// test/unit/settings_store_test.dart
// ================================================
// W4 (S39) — les réglages survivent au lancement. Avant : ProfileBloc naissait
// sur ProfileSettings() et rien n'écrivait nulle part — un évaluateur
// arabophone re-choisissait sa langue chaque matin.

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:epos_mobile/features/profile/data/settings_store.dart';
import 'package:epos_mobile/features/profile/domain/entities/profile_settings.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  test('store vide → réglages par défaut (français, clair)', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await SettingsStore.open();

    final settings = store.load();

    expect(settings.language, AppLanguage.french);
    expect(settings.themeMode, AppThemeMode.light);
  });

  test('save puis load → la langue et le thème survivent', () async {
    SharedPreferences.setMockInitialValues({});
    final store = await SettingsStore.open();

    await store.save(const ProfileSettings(
      language: AppLanguage.arabic,
      themeMode: AppThemeMode.dark,
    ));

    final relu = store.load();
    expect(relu.language, AppLanguage.arabic);
    expect(relu.themeMode, AppThemeMode.dark);
  });

  test('valeur inconnue en stockage → repli sur le défaut, sans jeter', () async {
    SharedPreferences.setMockInitialValues({
      'settings.language': 'zz',
      'settings.theme': 'plasma',
    });
    final store = await SettingsStore.open();

    final settings = store.load();

    expect(settings.language, AppLanguage.french);
    expect(settings.themeMode, AppThemeMode.light);
  });
}
