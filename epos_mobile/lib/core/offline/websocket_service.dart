// lib/core/offline/websocket_service.dart
// ================================================
// BF6.1 — Synchronisation temps réel via WebSocket STOMP.
//
// Permet au tableau de bord de l'évaluateur de recevoir les mises à
// jour de scores en temps réel depuis le backend Spring Boot (STOMP/SockJS).
//
// Topics :
//   /topic/stations/{stationId}/scores  → mise à jour score étudiant
//   /topic/lots/{lotId}/status          → changement de statut d'un lot
//
// Gestion de la reconnexion :
//   - Backoff exponentiel (1s → 2s → 4s → max 30s)
//   - Désactivé hors-ligne (ConnectivityService)
//   - Réactivé automatiquement au retour en ligne
//
// #197 — Le dashboard évaluateur (SessionBloc) a besoin de suivre l'état de
// PLUSIEURS lots à la fois (toute la liste de sessions affichée), pas un seul
// comme la grille de notation (GradingBloc, un seul lot ouvert à la fois).
// Les abonnements "lot" sont donc désormais gérés dans une map, au même
// principe que les abonnements "score" par station, et sont automatiquement
// réétablis après une coupure réseau (voir _onConnect).

import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

import '../constants/api_constants.dart';
import 'connectivity_service.dart';

/// Mise à jour de score reçue via WebSocket.
class ScoreUpdate {
  final int    etudiantId;
  final int    stationId;
  final double score;
  final bool   verrouille;

  const ScoreUpdate({
    required this.etudiantId,
    required this.stationId,
    required this.score,
    required this.verrouille,
  });

  factory ScoreUpdate.fromJson(Map<String, dynamic> json) => ScoreUpdate(
    etudiantId: json['etudiantId'] as int,
    stationId:  json['stationId']  as int,
    score:      (json['score'] as num).toDouble(),
    verrouille: json['verrouille'] as bool? ?? false,
  );
}

/// Notification de changement de statut d'un lot.
class LotStatusUpdate {
  final int    lotId;
  final String statut; // "EN_COURS" | "TERMINE"

  const LotStatusUpdate({required this.lotId, required this.statut});

  factory LotStatusUpdate.fromJson(Map<String, dynamic> json) => LotStatusUpdate(
    lotId:  json['lotId']  as int,
    statut: json['statut'] as String,
  );
}

class WebSocketService {
  WebSocketService._();
  static final WebSocketService instance = WebSocketService._();

  StompClient?                 _client;
  StreamSubscription<bool>?    _connectivitySub;
  Timer?                       _reconnectTimer;

  // Backoff exponentiel pour la reconnexion
  static const Duration _minBackoff = Duration(seconds: 1);
  static const Duration _maxBackoff = Duration(seconds: 30);
  Duration _currentBackoff = _minBackoff;

  bool _isConnected    = false;
  bool _shouldConnect  = false; // false = service arrêté intentionnellement
  String? _accessToken;

  // Souscriptions actives (stationId → StompUnsubscribe)
  final Map<int, StompUnsubscribe> _scoreSubscriptions = {};

  // #197 — Souscriptions actives (lotId → StompUnsubscribe). Plusieurs lots
  // peuvent être suivis simultanément (ex: dashboard listant N sessions),
  // contrairement à l'ancienne implémentation limitée à un seul lot.
  final Map<int, StompUnsubscribe> _lotSubscriptions = {};

  // Streams publics
  final _scoreController    = StreamController<ScoreUpdate>.broadcast();
  final _lotStatusController = StreamController<LotStatusUpdate>.broadcast();
  final _connectedController = StreamController<bool>.broadcast();

  Stream<ScoreUpdate>     get onScoreUpdate    => _scoreController.stream;
  Stream<LotStatusUpdate> get onLotStatusUpdate => _lotStatusController.stream;
  Stream<bool>            get onConnectionChanged => _connectedController.stream;
  bool                    get isConnected      => _isConnected;

  // ── Démarrage ────────────────────────────────────────────────────────────

  /// Initialise le service avec le token JWT de l'évaluateur connecté.
  /// À appeler après un login réussi.
  void init(String accessToken) {
    _accessToken   = accessToken;
    _shouldConnect = true;
    _currentBackoff = _minBackoff;

    // Branchement sur ConnectivityService
    _connectivitySub?.cancel();
    _connectivitySub = ConnectivityService.instance.onConnectivityChanged
        .listen((isOnline) {
      if (isOnline && _shouldConnect && !_isConnected) {
        debugPrint('[WS] Retour en ligne → reconnexion WebSocket');
        _connect();
      } else if (!isOnline) {
        debugPrint('[WS] Hors-ligne → WebSocket en attente');
        _disconnectClient();
      }
    });

    // Connexion initiale si en ligne
    if (ConnectivityService.instance.isOnline) {
      _connect();
    }
  }

  // ── Connexion STOMP ──────────────────────────────────────────────────────

  void _connect() {
    if (_client?.connected == true) return;
    if (_accessToken == null) return;

    final wsUrl = ApiConstants.wsUrl;

    _client = StompClient(
      config: StompConfig.sockJS(
        url: wsUrl,
        webSocketConnectHeaders: {
          'Authorization': 'Bearer $_accessToken',
        },
        onConnect:         _onConnect,
        onDisconnect:      _onDisconnect,
        onStompError:      _onStompError,
        onWebSocketError:  _onWebSocketError,
        reconnectDelay:    Duration.zero, // géré manuellement (backoff)
        heartbeatIncoming: const Duration(seconds: 20),
        heartbeatOutgoing: const Duration(seconds: 20),
      ),
    );

    _client!.activate();
    debugPrint('[WS] Connexion en cours vers $wsUrl');
  }

  void _onConnect(StompFrame frame) {
    _isConnected    = true;
    _currentBackoff = _minBackoff; // reset backoff sur succès
    _connectedController.add(true);
    debugPrint('[WS] ✅ Connecté au broker STOMP');

    // Ré-abonne aux topics actifs après reconnexion — #197 : la grille de
    // notation ET le dashboard doivent retrouver leurs abonnements sans que
    // l'utilisateur ait à rien faire (jamais de blocage de la saisie).
    for (final stationId in List.of(_scoreSubscriptions.keys)) {
      _subscribeToStation(stationId);
    }
    for (final lotId in List.of(_lotSubscriptions.keys)) {
      _subscribeToLot(lotId);
    }
  }

  void _onDisconnect(StompFrame frame) {
    _isConnected = false;
    _connectedController.add(false);
    debugPrint('[WS] Déconnecté du broker STOMP');
    if (_shouldConnect) _scheduleReconnect();
  }

  void _onStompError(StompFrame frame) {
    debugPrint('[WS] Erreur STOMP: ${frame.body}');
    if (_shouldConnect) _scheduleReconnect();
  }

  void _onWebSocketError(dynamic error) {
    debugPrint('[WS] Erreur WebSocket: $error');
    _isConnected = false;
    _connectedController.add(false);
    if (_shouldConnect) _scheduleReconnect();
  }

  void _scheduleReconnect() {
    if (!ConnectivityService.instance.isOnline) return;
    _reconnectTimer?.cancel();
    debugPrint('[WS] Reconnexion dans ${_currentBackoff.inSeconds}s');
    _reconnectTimer = Timer(_currentBackoff, () {
      if (_shouldConnect && !_isConnected) _connect();
    });
    // Backoff exponentiel
    final next = _currentBackoff * 2;
    _currentBackoff = next > _maxBackoff ? _maxBackoff : next;
  }

  // ── Souscriptions scores ─────────────────────────────────────────────────

  /// S'abonne aux scores d'une station (appelé par GradingBloc).
  void subscribeToStation(int stationId) {
    if (_scoreSubscriptions.containsKey(stationId)) return;
    _subscribeToStation(stationId);
  }

  void _subscribeToStation(int stationId) {
    if (_client?.connected != true) {
      // Mémorise pour ré-abonnement après reconnexion
      _scoreSubscriptions[stationId] = ({Map<String, String>? unsubscribeHeaders}) {};
      return;
    }

    final topic      = ApiConstants.wsScore(stationId);
    final unsubscribe = _client!.subscribe(
      destination: topic,
      callback:    (frame) => _handleScoreFrame(frame, stationId),
    );
    _scoreSubscriptions[stationId] = unsubscribe;
    debugPrint('[WS] Abonné à $topic');
  }

  void _handleScoreFrame(StompFrame frame, int stationId) {
    try {
      final json = jsonDecode(frame.body ?? '{}') as Map<String, dynamic>;
      final update = ScoreUpdate.fromJson(json);
      _scoreController.add(update);
    } catch (e) {
      debugPrint('[WS] Erreur parse score frame: $e');
    }
  }

  /// Se désabonne d'une station (appelé quand l'évaluateur quitte la grille).
  void unsubscribeFromStation(int stationId) {
    final unsub = _scoreSubscriptions.remove(stationId);
    unsub?.call();
  }

  // ── Souscriptions statut de lot (#197 — multi-lots) ─────────────────────

  /// S'abonne aux changements de statut d'un lot. Idempotent : un lot déjà
  /// suivi n'est pas ré-abonné.
  void subscribeToLot(int lotId) {
    if (_lotSubscriptions.containsKey(lotId)) return;
    _subscribeToLot(lotId);
  }

  void _subscribeToLot(int lotId) {
    if (_client?.connected != true) {
      // Mémorise pour ré-abonnement après reconnexion (même stratégie que
      // les scores) — jamais bloquant si le socket est momentanément coupé.
      _lotSubscriptions[lotId] = ({Map<String, String>? unsubscribeHeaders}) {};
      return;
    }

    final topic = ApiConstants.wsLotStatus(lotId);
    final unsubscribe = _client!.subscribe(
      destination: topic,
      callback:    (frame) {
        try {
          final json   = jsonDecode(frame.body ?? '{}') as Map<String, dynamic>;
          final update = LotStatusUpdate.fromJson(json);
          _lotStatusController.add(update);
        } catch (e) {
          debugPrint('[WS] Erreur parse lot status frame: $e');
        }
      },
    );
    _lotSubscriptions[lotId] = unsubscribe;
    debugPrint('[WS] Abonné à $topic');
  }

  /// Se désabonne d'un lot spécifique.
  void unsubscribeFromLot(int lotId) {
    final unsub = _lotSubscriptions.remove(lotId);
    unsub?.call();
  }

  /// #197 — Réconcilie les abonnements "lot" avec la liste de sessions
  /// actuellement affichée par le dashboard : désabonne les lots qui ne sont
  /// plus visibles, abonne les nouveaux. Appelé à chaque chargement/rafraîchissement
  /// de la liste de sessions (SessionBloc) pour que le live reste exact sans fuite
  /// d'abonnements STOMP.
  void syncLotSubscriptions(Iterable<int> lotIds) {
    final desired = lotIds.toSet();

    for (final lotId in _lotSubscriptions.keys.toList()) {
      if (!desired.contains(lotId)) {
        unsubscribeFromLot(lotId);
      }
    }
    for (final lotId in desired) {
      subscribeToLot(lotId);
    }
  }

  // ── Déconnexion ──────────────────────────────────────────────────────────

  /// Arrête le service proprement (à appeler au logout).
  void stop() {
    _shouldConnect = false;
    _reconnectTimer?.cancel();
    _connectivitySub?.cancel();
    _disconnectClient();
    _accessToken = null;
    _scoreSubscriptions.clear();
    _lotSubscriptions.clear();
  }

  void _disconnectClient() {
    _client?.deactivate();
    _isConnected = false;
  }

  void dispose() {
    stop();
    _scoreController.close();
    _lotStatusController.close();
    _connectedController.close();
  }
}
