// lib/core/network/api_client.dart
// ================================================
// Client HTTP Dio avec gestion automatique des tokens JWT

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:logger/logger.dart';
import 'package:pretty_dio_logger/pretty_dio_logger.dart';

import '../constants/api_constants.dart';

class ApiClient {
  late final Dio _dio;
  final FlutterSecureStorage _storage;
  final Logger _logger;

  static const String _accessTokenKey  = 'access_token';
  static const String _refreshTokenKey = 'refresh_token';

  ApiClient({
    required FlutterSecureStorage storage,
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

    _dio.interceptors.add(_JwtInterceptor(_storage, _dio, _logger));
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
}

// ─────────────────────────────────────────────────────────────────────────────
// Intercepteur JWT
// ─────────────────────────────────────────────────────────────────────────────
class _JwtInterceptor extends Interceptor {
  final FlutterSecureStorage _storage;
  final Dio    _dio;
  final Logger _logger;

  static const String _accessTokenKey  = 'access_token';
  static const String _refreshTokenKey = 'refresh_token';

  bool _isRefreshing = false;

  _JwtInterceptor(this._storage, this._dio, this._logger);

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
        final refreshToken = await _storage.read(key: _refreshTokenKey);
        if (refreshToken == null || refreshToken.isEmpty) {
          _isRefreshing = false;
          return handler.next(err);
        }

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