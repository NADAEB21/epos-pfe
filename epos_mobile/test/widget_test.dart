// test/widget_test.dart
// ================================================
// Test de smoke basique pour vérifier que l'app
// démarre correctement et affiche le login screen.
//
// #333 — CORRIGÉ : LoginScreen lit désormais ProfileBloc (context.watch, pour
// la langue et le thème) en plus d'AuthBloc. Le test ne fournissait que le
// second : ProviderNotFoundException levée avant le premier pump() ⇒ les deux
// tests échouaient en [E] (exception), pas sur une assertion. Ce n'est pas un
// comportement abandonné à réécrire — LoginScreen fonctionne normalement dans
// l'app réelle, où app.dart fournit toujours les deux blocs ensemble
// (MultiBlocProvider). Le test isolait un widget devenu incomplet sans son
// second provider.
//
// SharedPreferences mocké par précaution : ProfileBloc peut en dépendre
// (via SettingsStore, cf. test/unit/settings_store_test.dart) pour persister
// langue/thème ; sans mock, l'appel au plugin natif échoue en environnement
// de test VM.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:epos_mobile/features/auth/data/repositories/mock_auth_repository.dart';
import 'package:epos_mobile/features/auth/presentation/bloc/auth_bloc.dart';
import 'package:epos_mobile/features/auth/presentation/screens/login_screen.dart';
import 'package:epos_mobile/features/profile/presentation/bloc/profile_bloc.dart';

/// Même câblage que app.dart : LoginScreen a besoin d'AuthBloc ET de
/// ProfileBloc (langue + thème). Un seul MockAuthRepository suffit pour les
/// deux — ProfileBloc s'en sert pour le changement de mot de passe, pas pour
/// la lecture des réglages testée ici.
Widget _pumpableApp() {
  final authRepo = MockAuthRepository();
  return MultiBlocProvider(
    providers: [
      BlocProvider<AuthBloc>(create: (_) => AuthBloc(authRepository: authRepo)),
      BlocProvider<ProfileBloc>(create: (_) => ProfileBloc(authRepository: authRepo)),
    ],
    child: const MaterialApp(
      home: LoginScreen(),
    ),
  );
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  testWidgets('Login screen affiche les éléments de base', (WidgetTester tester) async {
    await tester.pumpWidget(_pumpableApp());
    await tester.pump();

    expect(find.text('EPOS'),            findsOneWidget);
    expect(find.text('Se connecter'),    findsOneWidget);
    expect(find.text('Mot de passe oublié ?'), findsOneWidget);
  });

  testWidgets('Login screen affiche une erreur avec mauvais identifiants', (WidgetTester tester) async {
    await tester.pumpWidget(_pumpableApp());
    await tester.pump();

    // Remplir avec de mauvais identifiants
    await tester.enterText(
      find.byType(TextFormField).first,
      'mauvais@email.com',
    );
    await tester.enterText(
      find.byType(TextFormField).last,
      'MauvaisPass1',
    );

    // Appuyer sur "Se connecter"
    await tester.tap(find.text('Se connecter'));
    await tester.pump(); // Déclenche le BLoC

    // Attendre la réponse du mock (1200ms de délai simulé)
    await tester.pump(const Duration(milliseconds: 1500));

    // Vérifier que le SnackBar d'erreur apparaît
    expect(find.text('Email ou mot de passe incorrect.'), findsOneWidget);
  });
}