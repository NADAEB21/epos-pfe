// test/widget_test.dart
// ================================================
// Test de smoke basique pour vérifier que l'app
// démarre correctement et affiche le login screen.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'package:epos_mobile/features/auth/data/repositories/mock_auth_repository.dart';
import 'package:epos_mobile/features/auth/presentation/bloc/auth_bloc.dart';
import 'package:epos_mobile/features/auth/presentation/screens/login_screen.dart';

void main() {
  testWidgets('Login screen affiche les éléments de base', (WidgetTester tester) async {
    // Construire le widget avec le mock repository
    await tester.pumpWidget(
      BlocProvider(
        create: (_) => AuthBloc(authRepository: MockAuthRepository()),
        child: const MaterialApp(
          home: LoginScreen(),
        ),
      ),
    );

    // Vérifier que les éléments principaux sont présents
    expect(find.text('EPOS'),            findsOneWidget);
    expect(find.text('Se connecter'),    findsOneWidget);
    expect(find.text('Mot de passe oublié ?'), findsOneWidget);
  });

  testWidgets('Login screen affiche une erreur avec mauvais identifiants', (WidgetTester tester) async {
    await tester.pumpWidget(
      BlocProvider(
        create: (_) => AuthBloc(authRepository: MockAuthRepository()),
        child: const MaterialApp(
          home: LoginScreen(),
        ),
      ),
    );

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