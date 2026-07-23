// lib/core/constants/api_constants.dart
// ================================================
// Centralise toutes les URLs et endpoints de l'API.
// ================================================

import 'dart:io' show Platform;
import 'package:flutter/foundation.dart';

class ApiConstants {
  ApiConstants._();

  /// URL de base de l'API REST (via gateway).
  ///
  /// Un `--dart-define=API_BASE_URL=…` explicite GAGNE TOUJOURS — y compris en debug.
  /// Avant, les branches kDebugMode court-circuitaient le define : impossible de pointer
  /// un serveur LAN (démo « PC de la faculté » : le téléphone doit viser l'IP du poste,
  /// pas son propre localhost). Les valeurs debug ne restent que des DÉFAUTS de confort.
  static String get baseUrl {
    const defined = String.fromEnvironment('API_BASE_URL');
    if (defined.isNotEmpty) return defined;
    if (kDebugMode) {
      if (kIsWeb) {
        return 'http://localhost:8080/api/v1';
      }
      if (Platform.isAndroid) {
        return 'http://10.0.2.2:8080/api/v1';
      }
    }
    return 'http://localhost:8080/api/v1';
  }

  /// URL de base du scoring-service (connexion WebSocket directe).
  ///
  /// BF6.1 — La connexion WebSocket STOMP/SockJS est établie directement
  /// avec le scoring-service et non via la gateway, car Spring Cloud Gateway
  /// nécessite une configuration supplémentaire pour le proxy WebSocket.
  ///
  /// Port 8083 = port interne du scoring-service (défini dans docker-compose).
  /// En production : utiliser une variable d'environnement WS_BASE_URL.
  static String get wsBaseUrl {
    // Même règle que baseUrl : un define explicite gagne, y compris en debug.
    const defined = String.fromEnvironment('WS_BASE_URL');
    if (defined.isNotEmpty) return defined;
    if (kDebugMode) {
      if (kIsWeb) {
        // Flutter Web : même machine, port scoring direct
        return 'http://localhost:8083';
      }
      if (Platform.isAndroid) {
        // Émulateur Android : 10.0.2.2 = hôte, port scoring direct
        return 'http://10.0.2.2:8083';
      }
    }
    return 'http://localhost:8083';
  }

  // === Auth ===
  static const String login   = '/auth/login';
  static const String logout  = '/auth/logout';
  static const String refresh = '/auth/refresh';
  static const String me      = '/auth/me';

  // === Mot de passe (BF1.3) ===
  /// Changement de mot de passe pour un utilisateur déjà connecté (écran Profil).
  static const String changePassword = '/auth/change-password';

  /// Demande de réinitialisation (utilisateur qui a oublié son mot de passe) —
  /// envoie un email contenant un token à usage unique, valide 30 minutes.
  static const String passwordResetRequest = '/auth/password-reset/request';

  /// Confirmation de la réinitialisation : { token, newPassword }.
  static const String passwordResetConfirm = '/auth/password-reset/confirm';

  // === Dashboard évaluateur ===
  static const String dashboard = '/evaluateur/dashboard';

  // === Grille (exam-service via gateway) ===
  static String stationGrille(int stationId) => '/stations/$stationId/grille';

  // === Lot + étudiants (scoring-service via gateway) ===
  // static String lotDetail(int stationId, int lotNumero) => '/evaluateur/stations/$stationId/lots/$lotNumero';

  // Remplace lotDetail(stationId, lotNumero)
 static String groupeDetail(int rotationId) => '/evaluateur/rotations/$rotationId/groupe';
 static String groupeSuivant(int rotationId) => '/evaluateur/rotations/$rotationId/suivant';

  // === Notation (scoring-service via gateway) ===
  static const String saisirNotation = '/evaluateur/notations/saisir';

  // === Validation ===
  static String validerEtudiant(int etudiantId, int stationId) =>
      '/evaluateur/etudiants/$etudiantId/stations/$stationId/valider';

  // static String validerLot(int lotId) => '/evaluateur/lots/$lotId/valider';
  // Remplace validerLot(lotId) et validerRotation(rotationId)
  static String validerGroupe(int rotationId) => '/evaluateur/rotations/$rotationId/valider';

  static String validerRotation(int rotationId) =>
      '/evaluateur/rotations/$rotationId/valider';

  // === Timeouts ===
  static const Duration connectTimeout = Duration(seconds: 20);
  static const Duration receiveTimeout = Duration(seconds: 20);

  // === WebSocket (BF6.1) ===
  /// URL de connexion SockJS vers le scoring-service.
  /// Format : http(s)://host:port/ws  (SockJS préfère http, pas ws)
  static String get wsUrl => '$wsBaseUrl/ws';

  /// Topic STOMP pour les mises à jour de score d'une station.
  static String wsScore(int stationId) => '/topic/stations/$stationId/scores';

  /// Topic STOMP pour les changements de statut d'un lot.
  static String wsLotStatus(int lotId) => '/topic/lots/$lotId/status';
}