// test/unit/profile_bloc_test.dart
// ================================================
// Tests unitaires de ProfileBloc — TOUS les événements.
//
// Deux volets :
//   1. Comportement du bloc en isolation (settingsStore=null autorisé,
//      cf. commentaire de ProfileBloc : « nullable pour tests/mode mock »).
//   2. Persistance RÉELLE via SettingsStore + SharedPreferences.setMockInitialValues
//      (même pattern que test/unit/settings_store_test.dart), pour prouver
//      que ProfileBloc écrit effectivement dans le store, pas seulement en
//      mémoire.

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:epos_mobile/features/auth/domain/repositories/auth_repository.dart';
import 'package:epos_mobile/features/profile/data/settings_store.dart';
import 'package:epos_mobile/features/profile/domain/entities/profile_settings.dart';
import 'package:epos_mobile/features/profile/presentation/bloc/profile_bloc.dart';

// ── Doublure de AuthRepository — seul changePassword nous intéresse ici ────
class _FakeAuthRepositoryForProfile implements AuthRepository {
  Future<void> Function(String current, String next)? onChangePassword;

  @override
  Future<void> changePassword({
    required String currentPassword,
    required String newPassword,
  }) async {
    if (onChangePassword != null) {
      await onChangePassword!(currentPassword, newPassword);
    }
  }

  // Le reste de l'interface n'est jamais exercé par ProfileBloc : tout appel
  // inattendu doit faire échouer le test bruyamment plutôt que silencieusement.
  @override
  dynamic noSuchMethod(Invocation invocation) =>
      throw UnimplementedError('inattendu dans ce test : ${invocation.memberName}');
}

Future<void> _settle() => Future<void>.delayed(const Duration(milliseconds: 60));

void main() {
  group('état initial', () {
    test('sans réglages fournis → ProfileSettings() par défaut (fr, clair)',
            () {
          final bloc = ProfileBloc(authRepository: _FakeAuthRepositoryForProfile());
          expect(bloc.state, isA<ProfileLoaded>());
          expect(bloc.state.settings.language, AppLanguage.french);
          expect(bloc.state.settings.themeMode, AppThemeMode.light);
          bloc.close();
        });

    test('avec initialSettings fournis (ex: lus depuis le store au démarrage)',
            () {
          final bloc = ProfileBloc(
            authRepository: _FakeAuthRepositoryForProfile(),
            initialSettings: const ProfileSettings(
              language: AppLanguage.arabic, themeMode: AppThemeMode.dark,
            ),
          );
          expect(bloc.state.settings.language, AppLanguage.arabic);
          expect(bloc.state.settings.themeMode, AppThemeMode.dark);
          bloc.close();
        });
  });

  group('ProfileLanguageChanged', () {
    test('met à jour la langue sans toucher au thème', () async {
      final bloc = ProfileBloc(authRepository: _FakeAuthRepositoryForProfile());
      bloc.add(const ProfileLanguageChanged(AppLanguage.english));
      await _settle();
      expect(bloc.state, isA<ProfileLoaded>());
      expect(bloc.state.settings.language, AppLanguage.english);
      expect(bloc.state.settings.themeMode, AppThemeMode.light);
      await bloc.close();
    });

    test('sans settingsStore, aucune exception n\'est levée', () async {
      final bloc = ProfileBloc(authRepository: _FakeAuthRepositoryForProfile());
      expect(
            () => bloc.add(const ProfileLanguageChanged(AppLanguage.arabic)),
        returnsNormally,
      );
      await _settle();
      expect(bloc.state.settings.language, AppLanguage.arabic);
      await bloc.close();
    });
  });

  group('ProfileThemeChanged', () {
    test('bascule vers le thème sombre', () async {
      final bloc = ProfileBloc(authRepository: _FakeAuthRepositoryForProfile());
      bloc.add(const ProfileThemeChanged(AppThemeMode.dark));
      await _settle();
      expect(bloc.state.settings.themeMode, AppThemeMode.dark);
      await bloc.close();
    });
  });

  group('Persistance réelle via SettingsStore', () {
    test('un changement de langue survit à un rechargement du store', () async {
      SharedPreferences.setMockInitialValues({});
      final store = await SettingsStore.open();
      final bloc = ProfileBloc(
        authRepository: _FakeAuthRepositoryForProfile(),
        settingsStore: store,
      );

      bloc.add(const ProfileLanguageChanged(AppLanguage.arabic));
      await _settle();

      final relu = store.load();
      expect(relu.language, AppLanguage.arabic);
      await bloc.close();
    });

    test('un changement de thème survit à un rechargement du store', () async {
      SharedPreferences.setMockInitialValues({});
      final store = await SettingsStore.open();
      final bloc = ProfileBloc(
        authRepository: _FakeAuthRepositoryForProfile(),
        settingsStore: store,
      );

      bloc.add(const ProfileThemeChanged(AppThemeMode.dark));
      await _settle();

      final relu = store.load();
      expect(relu.themeMode, AppThemeMode.dark);
      await bloc.close();
    });
  });

  group('ProfilePasswordChangeRequested', () {
    test('succès — ProfilePasswordChanging puis ProfilePasswordChanged',
            () async {
          final repo = _FakeAuthRepositoryForProfile()
            ..onChangePassword = (current, next) async {};
          final bloc = ProfileBloc(authRepository: repo);
          final states = <ProfileState>[];
          final sub = bloc.stream.listen(states.add);

          bloc.add(const ProfilePasswordChangeRequested(
            currentPassword: 'Ancien1234', newPassword: 'Nouveau1234',
          ));
          await _settle();
          await sub.cancel();

          expect(states, [isA<ProfilePasswordChanging>(), isA<ProfilePasswordChanged>()]);
          await bloc.close();
        });

    test('mot de passe actuel incorrect → ProfilePasswordError avec message',
            () async {
          final repo = _FakeAuthRepositoryForProfile()
            ..onChangePassword = (current, next) async =>
            throw Exception('Mot de passe actuel incorrect.');
          final bloc = ProfileBloc(authRepository: repo);
          final states = <ProfileState>[];
          final sub = bloc.stream.listen(states.add);

          bloc.add(const ProfilePasswordChangeRequested(
            currentPassword: 'Mauvais', newPassword: 'Nouveau1234',
          ));
          await _settle();
          await sub.cancel();

          expect(states, [isA<ProfilePasswordChanging>(), isA<ProfilePasswordError>()]);
          expect((states[1] as ProfilePasswordError).message,
              'Mot de passe actuel incorrect.');
          await bloc.close();
        });

    test('les réglages (langue/thème) sont préservés pendant le flux mot de passe',
            () async {
          final repo = _FakeAuthRepositoryForProfile()
            ..onChangePassword = (current, next) async {};
          final bloc = ProfileBloc(
            authRepository: repo,
            initialSettings: const ProfileSettings(
              language: AppLanguage.arabic, themeMode: AppThemeMode.dark,
            ),
          );

          bloc.add(const ProfilePasswordChangeRequested(
            currentPassword: 'A', newPassword: 'B12345678',
          ));
          await _settle();

          expect(bloc.state.settings.language, AppLanguage.arabic);
          expect(bloc.state.settings.themeMode, AppThemeMode.dark);
          await bloc.close();
        });
  });

  group('ProfilePasswordChangeDismissed', () {
    test('referme l\'état d\'erreur/succès et retombe sur ProfileLoaded',
            () async {
          final repo = _FakeAuthRepositoryForProfile()
            ..onChangePassword = (current, next) async =>
            throw Exception('Erreur serveur.');
          final bloc = ProfileBloc(authRepository: repo);

          bloc.add(const ProfilePasswordChangeRequested(
            currentPassword: 'x', newPassword: 'y12345678',
          ));
          await _settle();
          expect(bloc.state, isA<ProfilePasswordError>());

          bloc.add(const ProfilePasswordChangeDismissed());
          await _settle();
          expect(bloc.state, isA<ProfileLoaded>());
          await bloc.close();
        });
  });
}