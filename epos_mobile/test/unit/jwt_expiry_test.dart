// test/unit/jwt_expiry_test.dart
// ================================================
// #306 — le fournisseur de jeton du WebSocket décide localement s'il faut
// rafraîchir avant le CONNECT. La décision repose sur jwtExpiresBefore :
// une lecture, pas une validation — mais elle doit être JUSTE, sinon soit on
// rejoue un jeton mort (connexion refusée en boucle), soit on rafraîchit à
// chaque tentative (rotation inutile du refresh token).

import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/core/network/jwt_expiry.dart';

/// Fabrique un JWT NON signé mais structurellement valide (l'aide ne lit que
/// le payload — la signature est l'affaire du serveur).
String _jwt(Map<String, dynamic> payload) {
  String b64(Map<String, dynamic> m) =>
      base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
  return '${b64({'alg': 'HS384', 'typ': 'JWT'})}.${b64(payload)}.signature';
}

void main() {
  final now = DateTime.utc(2026, 8, 13, 12, 0, 0);
  int epoch(DateTime t) => t.millisecondsSinceEpoch ~/ 1000;

  test('jeton expirant dans 2 h → pas encore à renouveler', () {
    final jwt = _jwt({'exp': epoch(now.add(const Duration(hours: 2)))});
    expect(jwtExpiresBefore(jwt, now), isFalse);
  });

  test('jeton expiré depuis 1 min → à renouveler', () {
    final jwt = _jwt({'exp': epoch(now.subtract(const Duration(minutes: 1)))});
    expect(jwtExpiresBefore(jwt, now), isTrue);
  });

  test('la marge est respectée : expire dans 30 s, marge 60 s → à renouveler',
      () {
    final jwt = _jwt({'exp': epoch(now.add(const Duration(seconds: 30)))});
    expect(jwtExpiresBefore(jwt, now.add(const Duration(seconds: 60))), isTrue);
  });

  test('payload sans exp → à renouveler (jamais présumé valide)', () {
    expect(jwtExpiresBefore(_jwt({'sub': 'eval@epos.tn'}), now), isTrue);
  });

  test('chaîne qui n\'est pas un JWT → à renouveler, sans jeter', () {
    expect(jwtExpiresBefore('pas-un-jeton', now), isTrue);
    expect(jwtExpiresBefore('', now), isTrue);
    expect(jwtExpiresBefore('a.%%%.c', now), isTrue);
  });
}
