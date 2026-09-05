import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

/// #415 — le manifeste PRINCIPAL doit déclarer INTERNET.
///
/// Les manifestes `debug/` et `profile/` la portent « pour le développement »,
/// ce qui masque son absence tant qu'on ne fait que `flutter run`. Un build
/// release ne fusionne que `main/` : sans cette ligne, l'application installée
/// sur le téléphone ne peut ouvrir AUCUNE connexion (EACCES → « Connection
/// failed » à l'écran de login). Ce test casse la suite avant le téléphone.
void main() {
  test('main/AndroidManifest.xml déclare android.permission.INTERNET', () {
    final manifest = File('android/app/src/main/AndroidManifest.xml');
    expect(manifest.existsSync(), isTrue, reason: 'manifeste principal absent');
    final xml = manifest.readAsStringSync();
    expect(
      RegExp(r'<uses-permission\s+android:name="android\.permission\.INTERNET"\s*/>')
          .hasMatch(xml),
      isTrue,
      reason: 'INTERNET manque dans main/ — le build release ne joindra pas le backend (#415)',
    );
  });
}
