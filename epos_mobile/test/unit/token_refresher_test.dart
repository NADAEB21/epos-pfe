// test/unit/token_refresher_test.dart
// ================================================
// #306 — le rafraîchissement est à VOL PARTAGÉ, et ce n'est pas un détail :
// auth-service fait TOURNER le refresh token à chaque usage et révoque toute
// la famille s'il voit un ancien resservi (détection de vol). Deux appels
// concurrents — l'intercepteur sur un 401 REST et le fournisseur du WebSocket
// à la reconnexion — resserviraient précisément un ancien jeton, et
// l'évaluateur serait déconnecté en pleine épreuve « pour sa sécurité ».
// On prouve donc : N demandes concurrentes → UNE seule requête /auth/refresh.

import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:logger/logger.dart';

import 'package:epos_mobile/core/network/api_client.dart';
import 'package:epos_mobile/core/network/token_store.dart';

/// JWT non signé structurellement valide, expiré ou non selon [exp].
String _jwt(DateTime exp) {
  String b64(Map<String, dynamic> m) =>
      base64Url.encode(utf8.encode(jsonEncode(m))).replaceAll('=', '');
  return '${b64({'alg': 'HS384'})}.'
      '${b64({'exp': exp.millisecondsSinceEpoch ~/ 1000})}.sig';
}

/// Adaptateur HTTP factice : compte les requêtes /auth/refresh et répond
/// comme auth-service (avec un délai, pour laisser les appels se chevaucher).
class _CountingAdapter implements HttpClientAdapter {
  int refreshCalls = 0;
  final String newAccess;

  _CountingAdapter(this.newAccess);

  @override
  Future<ResponseBody> fetch(RequestOptions options,
      Stream<Uint8List>? requestStream, Future<void>? cancelFuture) async {
    if (options.path.contains('/auth/refresh')) {
      refreshCalls++;
      await Future<void>.delayed(const Duration(milliseconds: 150));
      return ResponseBody.fromString(
        jsonEncode({
          'success': true,
          'data': {'accessToken': newAccess, 'refreshToken': 'rot-$refreshCalls'},
        }),
        200,
        headers: {
          Headers.contentTypeHeader: [Headers.jsonContentType],
        },
      );
    }
    return ResponseBody.fromString('{}', 404);
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late ApiClient client;
  late _CountingAdapter adapter;
  final freshToken = _jwt(DateTime.now().add(const Duration(hours: 4)));

  setUp(() async {
    // En test, flutter_secure_storage n'a pas de plugin : TokenStore bascule
    // sur son repli mémoire à la première exception — exactement ce qu'il faut.
    client = ApiClient(
      storage: TokenStore(const FlutterSecureStorage()),
      logger: Logger(level: Level.off),
    );
    adapter = _CountingAdapter(freshToken);
    client.dio.httpClientAdapter = adapter;
  });

  test('jeton encore valide → rendu tel quel, AUCUN refresh', () async {
    await client.saveTokens(accessToken: freshToken, refreshToken: 'r1');

    final token = await client.getValidAccessToken();

    expect(token, freshToken);
    expect(adapter.refreshCalls, 0);
  });

  test('jeton expiré → UN refresh, le nouveau jeton est rendu et stocké',
      () async {
    final expired = _jwt(DateTime.now().subtract(const Duration(minutes: 5)));
    await client.saveTokens(accessToken: expired, refreshToken: 'r1');

    final token = await client.getValidAccessToken();

    expect(token, freshToken);
    expect(adapter.refreshCalls, 1);
    expect(await client.getAccessToken(), freshToken,
        reason: 'le jeton rafraîchi doit être écrit dans le stockage');
  });

  test('3 demandes CONCURRENTES → UNE seule requête /auth/refresh', () async {
    final expired = _jwt(DateTime.now().subtract(const Duration(minutes: 5)));
    await client.saveTokens(accessToken: expired, refreshToken: 'r1');

    final results = await Future.wait([
      client.getValidAccessToken(),
      client.getValidAccessToken(),
      client.getValidAccessToken(),
    ]);

    expect(results, everyElement(freshToken));
    expect(adapter.refreshCalls, 1,
        reason: 'deux refresh concurrents resserviraient un refresh token '
            'déjà tourné : auth-service y verrait un VOL et révoquerait '
            'toute la famille (breach detection)');
  });

  test('aucun jeton stocké → null, sans appel réseau', () async {
    expect(await client.getValidAccessToken(), isNull);
    expect(adapter.refreshCalls, 0);
  });
}
