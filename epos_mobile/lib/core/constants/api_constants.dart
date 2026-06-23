// lib/core/constants/api_constants.dart
// ================================================
// Centralise toutes les URLs et endpoints de l'API

import 'dart:io' show Platform;
import 'package:flutter/foundation.dart';

class ApiConstants {
  ApiConstants._();

  /// URL résolue à l'exécution selon la plateforme.
  static String get baseUrl {
    if (kDebugMode) {
      if (kIsWeb) {
        // Flutter Web (Chrome) : lancer avec flutter run -d chrome --web-port 4200 pour correspondre
        // à l'origine CORS autorisée par la gateway.
        return 'http://localhost:8080/api/v1';
      }
      if (Platform.isAndroid) {
        // Émulateur Android : 10.0.2.2 = localhost de la machine hôte.
        // Appareil physique : remplacer par l'IP LAN de la machine
        // ex : return 'http://192.168.1.XXX:8080/api/v1';
        return 'http://10.0.2.2:8080/api/v1';
      }
    }
    // Production ou override via --dart-define=API_BASE_URL=...
    return const String.fromEnvironment(
      'API_BASE_URL',
      defaultValue: 'http://localhost:8080/api/v1',
    );
  }

  // === Auth ===
  static const String login   = '/auth/login';
  static const String logout  = '/auth/logout';
  static const String refresh = '/auth/refresh';
  static const String me      = '/auth/me';

  // === Dashboard évaluateur ===
  static const String dashboard = '/evaluateur/dashboard';

  // === Grille (exam-service via gateway) ===
  static String stationGrille(int stationId) => '/stations/$stationId/grille';

  // === Lot + étudiants (scoring-service via gateway) ===
  static String lotDetail(int stationId, int lotNumero) =>
      '/evaluateur/stations/$stationId/lots/$lotNumero';

  // === Notation (scoring-service via gateway) ===
  static const String saisirNotation = '/evaluateur/notations/saisir';

  // === Validation ===
  static String validerEtudiant(int etudiantId, int stationId) =>
      '/evaluateur/etudiants/$etudiantId/stations/$stationId/valider';

  static String validerLot(int lotId) => '/evaluateur/lots/$lotId/valider';

  // === Timeouts ===
  static const Duration connectTimeout = Duration(seconds: 20); //10s en prod
  static const Duration receiveTimeout = Duration(seconds: 20); //15s en prod

  // === WebSocket ===
  static String get wsUrl              => baseUrl.replaceFirst('/api/v1', '/ws');
  static String wsScore(int stationId) => '/topic/stations/$stationId/scores';
}