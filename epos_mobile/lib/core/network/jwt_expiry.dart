// lib/core/network/jwt_expiry.dart
// ================================================
// #306 — lecture LOCALE de l'expiration d'un JWT.
//
// Le fournisseur de jeton du WebSocket doit savoir si le jeton stocké est
// encore présentable AVANT le CONNECT (désormais fermé côté scoring), sans
// dépendre du réseau. On ne VALIDE rien ici — pas de signature, pas de
// confiance : on lit une date que le client a le droit de lire, pour décider
// s'il faut rafraîchir. La validation reste au serveur.

import 'dart:convert';

/// Vrai si le jeton expire avant [instant] — ou s'il est illisible :
/// un jeton qu'on ne sait pas lire est un jeton à renouveler.
bool jwtExpiresBefore(String jwt, DateTime instant) {
  try {
    final parts = jwt.split('.');
    if (parts.length != 3) return true;
    final payload = jsonDecode(
      utf8.decode(base64Url.decode(base64Url.normalize(parts[1]))),
    ) as Map<String, dynamic>;
    final exp = payload['exp'];
    if (exp is! num) return true;
    final expiresAt =
        DateTime.fromMillisecondsSinceEpoch(exp.toInt() * 1000, isUtc: true);
    return expiresAt.isBefore(instant.toUtc());
  } catch (_) {
    return true;
  }
}
