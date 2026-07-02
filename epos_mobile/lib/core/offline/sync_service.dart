// lib/core/offline/sync_service.dart
// ================================================
// BF6.2 — Synchronisation automatique des notations hors-ligne.
//
// Déclenché automatiquement dès que ConnectivityService détecte
// un retour en ligne. Envoie les PendingNotations par lots pour
// éviter de surcharger le backend (max 10 par batch).
//
// Stratégie d'échec : les notations en erreur voient leur retry_count
// incrémenté. Après MAX_RETRIES, elles sont abandonnées et loguées
// (l'évaluateur est notifié via le SyncBloc).

import 'dart:async';

import 'package:flutter/foundation.dart';

import '../../features/grading/domain/repositories/grading_repository.dart';
import 'connectivity_service.dart';
import 'offline_storage_service.dart';

/// Résultat d'une opération de synchronisation.
class SyncResult {
  final int synced;
  final int failed;
  final int abandoned; // dépassé MAX_RETRIES
  final DateTime syncedAt;

  const SyncResult({
    required this.synced,
    required this.failed,
    required this.abandoned,
    required this.syncedAt,
  });

  bool get hasFailures => failed > 0 || abandoned > 0;
  bool get isComplete  => synced > 0 && failed == 0;
}

class SyncService {
  SyncService._();
  static final SyncService instance = SyncService._();

  static const int _batchSize  = 10;
  static const int _maxRetries = 3;

  GradingRepository? _repository;
  StreamSubscription<bool>? _connectivitySub;

  final _syncResultController = StreamController<SyncResult>.broadcast();
  final _syncProgressController = StreamController<bool>.broadcast(); // true = en cours

  /// Résultats de synchronisation (écouter pour mettre à jour l'UI).
  Stream<SyncResult> get onSyncResult => _syncResultController.stream;

  /// true quand une synchronisation est en cours.
  Stream<bool> get onSyncProgress => _syncProgressController.stream;

  bool _isSyncing = false;

  // ── Initialisation ───────────────────────────────────────────────────────

  /// Doit être appelé une seule fois après la création du repository.
  /// Se branche sur ConnectivityService pour déclencher la sync au retour en ligne.
  void init(GradingRepository repository) {
    _repository = repository;

    // Écoute les changements de connectivité
    _connectivitySub = ConnectivityService.instance.onConnectivityChanged
        .listen((isOnline) {
      if (isOnline) {
        debugPrint('[SyncService] Retour en ligne → déclenchement sync');
        syncNow();
      }
    });
  }

  // ── Synchronisation ──────────────────────────────────────────────────────

  /// Synchronise toutes les notations en attente.
  /// Idempotent : plusieurs appels simultanés → un seul s'exécute.
  Future<SyncResult?> syncNow() async {
    if (_isSyncing)        return null;
    if (_repository == null) return null;

    final pending = await OfflineStorageService.instance.getPendingNotations();
    if (pending.isEmpty) return null;

    _isSyncing = true;
    _syncProgressController.add(true);
    debugPrint('[SyncService] Début sync : ${pending.length} notation(s) en attente');

    int synced    = 0;
    int failed    = 0;
    int abandoned = 0;

    // Traitement par lots pour ne pas saturer le serveur
    for (int i = 0; i < pending.length; i += _batchSize) {
      final batch = pending.sublist(
        i,
        (i + _batchSize).clamp(0, pending.length),
      );

      final successIds = <int>[];
      final failedIds  = <int>[];
      final abandonIds = <int>[];

      for (final pn in batch) {
        if (pn.retryCount >= _maxRetries) {
          // Abandonne : trop d'échecs → on ne bloque pas indéfiniment
          if (pn.id != null) abandonIds.add(pn.id!);
          abandoned++;
          continue;
        }

        try {
          await _repository!.saveNotation(pn.toNotation());
          if (pn.id != null) successIds.add(pn.id!);
          synced++;
        } catch (e) {
          debugPrint('[SyncService] Échec notation id=${pn.id}: $e');
          if (pn.id != null) failedIds.add(pn.id!);
          failed++;
        }
      }

      // Nettoyage base locale
      await OfflineStorageService.instance.deleteByIds(successIds);
      await OfflineStorageService.instance.deleteByIds(abandonIds);
      await OfflineStorageService.instance.incrementRetry(failedIds);
    }

    // Journal
    await OfflineStorageService.instance.logSync(
      count:   synced + failed,
      success: failed == 0,
    );

    final result = SyncResult(
      synced:    synced,
      failed:    failed,
      abandoned: abandoned,
      syncedAt:  DateTime.now(),
    );

    debugPrint(
      '[SyncService] Fin sync : $synced OK, $failed échecs, $abandoned abandonnés',
    );

    _isSyncing = false;
    _syncProgressController.add(false);
    _syncResultController.add(result);

    return result;
  }

  /// Nombre de notations locales en attente (pour le badge).
  Future<int> getPendingCount() =>
      OfflineStorageService.instance.getPendingCount();

  // ── Nettoyage ────────────────────────────────────────────────────────────

  void dispose() {
    _connectivitySub?.cancel();
    _syncResultController.close();
    _syncProgressController.close();
  }
}