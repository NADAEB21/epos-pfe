// lib/core/network/api_client.dart
// ================================================
// Client HTTP Dio avec gestion automatique des tokens JWT

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'token_store.dart';
import 'package:logger/logger.dart';
import 'package:pretty_dio_logger/pretty_dio_logger.dart';

import '../constants/api_constants.dart';
import 'jwt_expiry.dart';

class ApiClient {
  late final Dio _dio;
  final TokenStore _storage;
  final Logger _logger;
  late final _TokenRefresher _refresher;

  static const String _accessTokenKey  = 'access_token';
  static const String _refreshTokenKey = 'refresh_token';

  ApiClient({
    required TokenStore storage,
    required Logger logger,
  })  : _storage = storage,
        _logger = logger {
    _dio = Dio(
      BaseOptions(
        baseUrl:        ApiConstants.baseUrl,
        connectTimeout: ApiConstants.connectTimeout,
        receiveTimeout: ApiConstants.receiveTimeout,
        headers: {'Content-Type': 'application/json'},
      ),
    );

    // PrettyDioLogger en premier pour voir les requêtes/réponses brutes.
    // Actif uniquement en mode debug pour ne pas exposer les tokens en prod.
    if (kDebugMode) {
      _dio.interceptors.add(
        PrettyDioLogger(
          requestHeader: true,
          requestBody:   true,
          responseBody:  true,
          error:         true,
          compact:       false,
        ),
      );
    }

    _refresher = _TokenRefresher(_storage, _dio);
    _dio.interceptors.add(_JwtInterceptor(_storage, _dio, _logger, _refresher));
  }

  Dio get dio => _dio;

  Future<Response<T>> get<T>(String path, {Map<String, dynamic>? params}) =>
      _dio.get<T>(path, queryParameters: params);

  Future<Response<T>> post<T>(String path, {dynamic data}) =>
      _dio.post<T>(path, data: data);

  Future<Response<T>> put<T>(String path, {dynamic data}) =>
      _dio.put<T>(path, data: data);

  Future<Response<T>> delete<T>(String path) =>
      _dio.delete<T>(path);

  Future<void> saveTokens({
    required String accessToken,
    required String refreshToken,
  }) async {
    await _storage.write(key: _accessTokenKey,  value: accessToken);
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
  }

  Future<void> clearTokens() async => _storage.deleteAll();

  Future<String?> getAccessToken() => _storage.read(key: _accessTokenKey);

  /// #306 — jeton d'accès garanti UTILISABLE, pour la connexion WebSocket.
  ///
  /// Le STOMP présente son jeton une seule fois, au CONNECT — hors du chemin
  /// des intercepteurs Dio. Avec le TTL raccourci à 4 h et le CONNECT fermé
  /// côté scoring, relire simplement le stockage rejouerait un jeton expiré à
  /// l'infini. On rafraîchit donc ICI quand l'expiration est là (marge 60 s),
  /// via la MÊME routine que l'intercepteur : deux rafraîchissements
  /// concurrents, avec la rotation des refresh tokens, seraient lus par
  /// auth-service comme une réutilisation volée et révoqueraient la famille.
  Future<String?> getValidAccessToken() async {
    final token = await _storage.read(key: _accessTokenKey);
    if (token == null || token.isEmpty) return null;
    if (!jwtExpiresBefore(token, DateTime.now().add(const Duration(seconds: 60)))) {
      return token;
    }
    try {
      return await _refresher.refresh() ?? token;
    } catch (_) {
      // Hors ligne, ou refresh token révoqué : on rend le jeton tel quel.
      // Le CONNECT échouera et la reconnexion à backoff retentera — le
      // fournisseur ne doit JAMAIS bloquer ni jeter vers le WebSocketService.
      return token;
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rafraîchissement des tokens — routine UNIQUE, à vol partagé (#306)
// ─────────────────────────────────────────────────────────────────────────────
// auth-service fait tourner le refresh token à chaque usage et révoque toute
// la famille s'il voit un ancien resservi (détection de vol). DEUX appels
// concurrents — l'intercepteur sur un 401 REST et le fournisseur du WebSocket
// à la reconnexion — resserviraient précisément un ancien jeton. Tout
// demandeur concurrent attend donc le MÊME Future.
class _TokenRefresher {
  final TokenStore _storage;
  final Dio _dio;

  static const String _accessTokenKey  = 'access_token';
  static const String _refreshTokenKey = 'refresh_token';

  Future<String?>? _inFlight;

  _TokenRefresher(this._storage, this._dio);

  /// Rend le nouveau jeton d'accès, ou null si aucun refresh token n'existe.
  /// Une erreur réseau/HTTP est propagée à TOUS les appelants en attente.
  Future<String?> refresh() {
    return _inFlight ??= _doRefresh().whenComplete(() => _inFlight = null);
  }

  Future<String?> _doRefresh() async {
    final refreshToken = await _storage.read(key: _refreshTokenKey);
    if (refreshToken == null || refreshToken.isEmpty) return null;

    final response = await _dio.post(
      ApiConstants.refresh,
      data: {'refreshToken': refreshToken},
      options: Options(headers: {'Authorization': null}),
    );

    final body       = response.data as Map<String, dynamic>;
    final tokens     = body['data']           as Map<String, dynamic>;
    final newAccess  = tokens['accessToken']  as String;
    final newRefresh = tokens['refreshToken'] as String;

    await _storage.write(key: _accessTokenKey,  value: newAccess);
    await _storage.write(key: _refreshTokenKey, value: newRefresh);
    return newAccess;
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Intercepteur JWT
// ─────────────────────────────────────────────────────────────────────────────
class _JwtInterceptor extends Interceptor {
  final TokenStore _storage;
  final Dio    _dio;
  final Logger _logger;
  final _TokenRefresher _refresher;

  static const String _accessTokenKey = 'access_token';

  bool _isRefreshing = false;

  _JwtInterceptor(this._storage, this._dio, this._logger, this._refresher);

  @override
  void onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (_isPublicRoute(options.path)) {
      return handler.next(options);
    }
    final token = await _storage.read(key: _accessTokenKey);
    if (token != null && token.isNotEmpty) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    if (err.response?.statusCode == 401 && !_isRefreshing) {
      _isRefreshing = true;
      try {
        // #306 — le rafraîchissement lui-même vit dans _TokenRefresher,
        // partagé avec getValidAccessToken() (voir la note de la classe).
        final newAccess = await _refresher.refresh();
        if (newAccess == null) {
          _isRefreshing = false;
          return handler.next(err);
        }

        err.requestOptions.headers['Authorization'] = 'Bearer $newAccess';
        final retryResponse = await _dio.fetch(err.requestOptions);
        _isRefreshing = false;
        return handler.resolve(retryResponse);

      } catch (e) {
        _isRefreshing = false;
        _logger.e('Token refresh failed', error: e);
        // Nettoyage protégé — deleteAll() ne doit pas bloquer la chaîne
        try { await _storage.deleteAll(); } catch (_) {}
        return handler.next(err);
      }
    }
    handler.next(err);
  }

  bool _isPublicRoute(String path) =>
      path.contains('/auth/login') || path.contains('/auth/refresh');
}