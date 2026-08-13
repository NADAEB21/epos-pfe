// test/unit/ws_wiring_test.dart
// ================================================
// #306 — LE FIL MANQUANT, deuxième occurrence (après #307/SyncService).
//
// Le WebSocket était complet : service, backoff, abonnements, ré-abonnements.
// Il manquait UNE ligne — app.dart construisait AuthBloc sans getAccessToken,
// donc _startWebSocket() sortait à sa première ligne et l'app réelle n'a
// JAMAIS ouvert de connexion STOMP. Chaque pièce fonctionnait ; l'assemblage
// manquait. On vérifie donc l'assemblage lui-même, à la source — même
// approche volontairement rustique que test/unit/sync_wiring_test.dart.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('#306 — app.dart câble bien le fournisseur de jeton dans AuthBloc', () {
    final source = File('lib/app.dart').readAsStringSync();

    expect(
      source.contains('getAccessToken: widget.apiClient?.getValidAccessToken'),
      isTrue,
      reason:
          'app.dart doit passer getValidAccessToken à AuthBloc. Sans ce '
          'paramètre, _startWebSocket() est un no-op silencieux et l\'app '
          'n\'ouvre JAMAIS de connexion STOMP (#306).',
    );
  });

  test('#306 — le fournisseur est celui qui RAFRAÎCHIT (pas la simple lecture)',
      () {
    final apiClient =
        File('lib/core/network/api_client.dart').readAsStringSync();

    // getValidAccessToken doit exister et consulter l'expiration avant de
    // rendre le jeton : c'est ce qui permet à une reconnexion WebSocket
    // d'aboutir après l'expiration du jeton du login (TTL 4 h).
    expect(apiClient.contains('Future<String?> getValidAccessToken()'), isTrue);
    expect(apiClient.contains('jwtExpiresBefore('), isTrue,
        reason: 'getValidAccessToken doit tester l\'expiration, sinon il '
            'rejoue un jeton mort à chaque reconnexion.');
  });

  test('#306 — le jeton vit dans la frame CONNECT, pas seulement la poignée '
      'de main', () {
    final ws =
        File('lib/core/offline/websocket_service.dart').readAsStringSync();

    expect(ws.contains('stompConnectHeaders'), isTrue,
        reason: 'Sans jeton dans la frame CONNECT, la connexion STOMP était '
            'anonyme — elle ne tenait que par le repli fail-open que #306 '
            'supprime côté scoring.');
  });
}
