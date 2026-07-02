// lib/core/offline/connectivity_service.dart
// ================================================
// BF6.2 — Détection de la connectivité réseau en temps réel.
//
// Expose un Stream<bool> (isOnline) que les BLoCs et widgets
// peuvent écouter pour adapter leur comportement.
//
// Stratégie : polling actif vers le backend (HEAD /actuator/health)
// plutôt que de se fier aux APIs OS, car un réseau peut être présent
// mais le serveur Spring Boot inaccessible (tunnel VPN, LAN intranet).
//
// Fréquence : vérification toutes les 5 secondes quand hors-ligne,
//             toutes les 30 secondes quand en ligne (économie batterie).

import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../constants/api_constants.dart';

enum ConnectivityStatus { online, offline, checking }

class ConnectivityService {
  ConnectivityService._();
  static final ConnectivityService instance = ConnectivityService._();

  // Intervales de polling (hors-ligne : fréquent pour détecter le retour)
  static const Duration _pollOnline  = Duration(seconds: 30);
  static const Duration _pollOffline = Duration(seconds: 5);
  static const Duration _timeout     = Duration(seconds: 4);

  final _controller = StreamController<bool>.broadcast();
  final _dio        = Dio();

  Timer?             _timer;
  bool               _isOnline  = true;  // optimiste au démarrage
  ConnectivityStatus _status    = ConnectivityStatus.checking;

  /// Stream émettant `true` (en ligne) ou `false` (hors-ligne).
  Stream<bool> get onConnectivityChanged => _controller.stream;

  /// Dernière valeur connue.
  bool get isOnline => _isOnline;

  ConnectivityStatus get status => _status;

  // ── Démarrage ────────────────────────────────────────────────────────────

  /// Démarre les vérifications périodiques. À appeler une seule fois
  /// dans main() après initialisation de l'app.
  void start() {
    _check(); // vérification immédiate
    _scheduleNext();
  }

  void _scheduleNext() {
    _timer?.cancel();
    final interval = _isOnline ? _pollOnline : _pollOffline;
    _timer = Timer(interval, () {
      _check();
      _scheduleNext();
    });
  }

  // ── Vérification ─────────────────────────────────────────────────────────

  Future<void> _check() async {
    // En mode test/debug web, on peut simuler sans réseau réel
    if (kIsWeb && kDebugMode) {
      _emit(true);
      return;
    }

    try {
      // HEAD est ultra-léger : pas de body, juste le statut HTTP
      final healthUrl =
          '${ApiConstants.baseUrl.replaceFirst('/api/v1', '')}/actuator/health';

      final response = await _dio.head<void>(
        healthUrl,
        options: Options(
          sendTimeout:    _timeout,
          receiveTimeout: _timeout,
          validateStatus: (status) => status != null && status < 500,
        ),
      );
      _emit(response.statusCode != null);
    } catch (_) {
      _emit(false);
    }
  }

  void _emit(bool online) {
    final changed = online != _isOnline;
    _isOnline = online;
    _status   = online ? ConnectivityStatus.online : ConnectivityStatus.offline;

    if (changed) {
      _controller.add(online);
      debugPrint('[Connectivity] ${online ? "🟢 En ligne" : "🔴 Hors-ligne"}');
    }
  }

  /// Force une vérification immédiate (utile après une action utilisateur).
  Future<bool> checkNow() async {
    await _check();
    return _isOnline;
  }

  // ── Nettoyage ────────────────────────────────────────────────────────────

  void dispose() {
    _timer?.cancel();
    _controller.close();
  }
}