// integration_test/smoke_test.dart
//
// Étape 1 : prouver que le harnais E2E fonctionne, AVANT d'écrire des
// scénarios. Boote l'app réelle et vérifie qu'on atteint l'écran de login.
//
// Lancement (voir README.md du dossier) :
//   chromedriver --port=4444
//   flutter drive --driver=test_driver/integration_test.dart \
//                 --target=integration_test/smoke_test.dart -d chrome
//
// ⚠️ Contrairement à `flutter test`, ceci exécute l'app RÉELLE : elle appelle
// le vrai backend sur localhost:8080. La pile Docker doit tourner.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';

import 'package:epos_mobile/main.dart' as app;

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('smoke — l\'app boote et affiche l\'écran de connexion',
      (tester) async {
    app.main();
    // pumpAndSettle est risqué si une animation tourne en boucle ; on borne.
    await tester.pumpAndSettle(const Duration(seconds: 10));

    // L'app démarre sur le check d'auth puis retombe sur le login.
    expect(find.byType(TextField), findsWidgets,
        reason: 'les champs email/mot de passe doivent être présents');
  });
}
