// lib/core/offline/offline_bloc.dart
// ================================================
// BF6.2 — BLoC central pour la gestion de l'état hors-ligne.
//
// Centralise :
//   • L'état de connectivité (online / offline)
//   • L'état de synchronisation (idle / syncing / result)
//   • Le nombre de notations en attente (badge)
//
// Consommé par :
//   • ConnectivityBanner (widget UI persistant)
//   • GradingBloc (décide de la stratégie save : direct vs local)

import 'dart:async';

import 'package:equatable/equatable.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import 'connectivity_service.dart';
import 'sync_service.dart';

// ── Events ───────────────────────────────────────────────────────────────────

abstract class OfflineEvent extends Equatable {
  const OfflineEvent();
  @override
  List<Object?> get props => [];
}

/// Changement de connectivité détecté.
class OfflineConnectivityChanged extends OfflineEvent {
  final bool isOnline;
  const OfflineConnectivityChanged(this.isOnline);
  @override
  List<Object?> get props => [isOnline];
}

/// Sync en cours / terminée.
class OfflineSyncProgressChanged extends OfflineEvent {
  final bool isSyncing;
  const OfflineSyncProgressChanged(this.isSyncing);
  @override
  List<Object?> get props => [isSyncing];
}

/// Résultat d'une synchronisation reçu.
class OfflineSyncCompleted extends OfflineEvent {
  final SyncResult result;
  const OfflineSyncCompleted(this.result);
  @override
  List<Object?> get props => [result];
}

/// Demande de synchronisation manuelle.
class OfflineSyncRequested extends OfflineEvent {
  const OfflineSyncRequested();
}

/// Mise à jour du nombre de notations en attente.
class OfflinePendingCountUpdated extends OfflineEvent {
  final int count;
  const OfflinePendingCountUpdated(this.count);
  @override
  List<Object?> get props => [count];
}

// ── States ───────────────────────────────────────────────────────────────────

/// État d'une synchronisation.
enum SyncStatus { idle, syncing, success, partialFailure }

class OfflineState extends Equatable {
  final bool       isOnline;
  final bool       isSyncing;
  final SyncStatus syncStatus;
  final int        pendingCount; // notations en attente
  final int        lastSyncedCount; // notations envoyées lors de la dernière sync
  final DateTime?  lastSyncAt;

  const OfflineState({
    this.isOnline       = true,
    this.isSyncing      = false,
    this.syncStatus     = SyncStatus.idle,
    this.pendingCount   = 0,
    this.lastSyncedCount = 0,
    this.lastSyncAt,
  });

  OfflineState copyWith({
    bool?       isOnline,
    bool?       isSyncing,
    SyncStatus? syncStatus,
    int?        pendingCount,
    int?        lastSyncedCount,
    DateTime?   lastSyncAt,
  }) => OfflineState(
    isOnline:        isOnline        ?? this.isOnline,
    isSyncing:       isSyncing       ?? this.isSyncing,
    syncStatus:      syncStatus      ?? this.syncStatus,
    pendingCount:    pendingCount    ?? this.pendingCount,
    lastSyncedCount: lastSyncedCount ?? this.lastSyncedCount,
    lastSyncAt:      lastSyncAt      ?? this.lastSyncAt,
  );

  @override
  List<Object?> get props => [
    isOnline, isSyncing, syncStatus, pendingCount, lastSyncedCount, lastSyncAt,
  ];
}

// ── BLoC ─────────────────────────────────────────────────────────────────────

class OfflineBloc extends Bloc<OfflineEvent, OfflineState> {
  StreamSubscription<bool>?       _connectivitySub;
  StreamSubscription<bool>?       _syncProgressSub;
  StreamSubscription<SyncResult>? _syncResultSub;

  OfflineBloc() : super(OfflineState(isOnline: ConnectivityService.instance.isOnline)) {
    on<OfflineConnectivityChanged>  (_onConnectivityChanged);
    on<OfflineSyncProgressChanged>  (_onSyncProgressChanged);
    on<OfflineSyncCompleted>        (_onSyncCompleted);
    on<OfflineSyncRequested>        (_onSyncRequested);
    on<OfflinePendingCountUpdated>  (_onPendingCountUpdated);

    _connectivitySub = ConnectivityService.instance.onConnectivityChanged
        .listen((online) => add(OfflineConnectivityChanged(online)));

    _syncProgressSub = SyncService.instance.onSyncProgress
        .listen((syncing) => add(OfflineSyncProgressChanged(syncing)));

    _syncResultSub = SyncService.instance.onSyncResult
        .listen((result) => add(OfflineSyncCompleted(result)));

    // Charge le nombre initial de notations en attente
    _refreshPendingCount();
  }

  // ── Handlers ──────────────────────────────────────────────────────────────

  void _onConnectivityChanged(
    OfflineConnectivityChanged event,
    Emitter<OfflineState> emit,
  ) {
    emit(state.copyWith(isOnline: event.isOnline));
  }

  void _onSyncProgressChanged(
    OfflineSyncProgressChanged event,
    Emitter<OfflineState> emit,
  ) {
    emit(state.copyWith(
      isSyncing:  event.isSyncing,
      syncStatus: event.isSyncing ? SyncStatus.syncing : state.syncStatus,
    ));
  }

  void _onSyncCompleted(
    OfflineSyncCompleted event,
    Emitter<OfflineState> emit,
  ) async {
    final r      = event.result;
    final status = r.isComplete ? SyncStatus.success : SyncStatus.partialFailure;

    // Recharge le compte après sync
    final remaining = await SyncService.instance.getPendingCount();

    emit(state.copyWith(
      isSyncing:       false,
      syncStatus:      status,
      pendingCount:    remaining,
      lastSyncedCount: r.synced,
      lastSyncAt:      r.syncedAt,
    ));
  }

  Future<void> _onSyncRequested(
    OfflineSyncRequested event,
    Emitter<OfflineState> emit,
  ) async {
    if (!state.isOnline) return;
    await SyncService.instance.syncNow();
  }

  Future<void> _onPendingCountUpdated(
    OfflinePendingCountUpdated event,
    Emitter<OfflineState> emit,
  ) async {
    emit(state.copyWith(pendingCount: event.count));
  }

  /// Appelé depuis GradingBloc après chaque sauvegarde locale.
  Future<void> refreshPendingCount() => _refreshPendingCount();

  Future<void> _refreshPendingCount() async {
    final count = await SyncService.instance.getPendingCount();
    add(OfflinePendingCountUpdated(count));
  }

  @override
  Future<void> close() {
    _connectivitySub?.cancel();
    _syncProgressSub?.cancel();
    _syncResultSub?.cancel();
    return super.close();
  }
}