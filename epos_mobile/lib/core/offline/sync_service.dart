// lib/core/offline/sync_service.dart
// ================================================
// BF6.2 — Synchronisation des notations saisies hors-ligne.
//
// Déclenché automatiquement dès que ConnectivityService détecte
// un retour en ligne. Envoie les PendingNotations par lots pour
// éviter de surcharger le backend (max 10 par batch).
//
// ⛔ #307 — RÈGLE CARDINALE DE CE FICHIER
//
//   UNE NOTATION N'EST SUPPRIMÉE QU'APRÈS UN 2xx DU SERVEUR.
//
// Le téléphone est le SEUL dépositaire d'une note saisie hors ligne. La
// supprimer sans confirmation, c'est effacer la note d'un étudiant — elle
// n'existe alors plus nulle part, et l'examen est terminé avant que
// quiconque s'en aperçoive.
//
// Deux défauts corrigés ici :
//
//  1. La boucle appelait `saveNotation()`, qui AVALE les erreurs réseau et
//     réenregistre en local avant de retourner normalement. Un simple délai
//     d'attente était donc compté comme un succès — puis supprimé. On utilise
//     désormais `pushNotation()`, qui LÈVE au lieu de se taire.
//  2. Après 3 échecs, la notation était `DELETE`. Elle passe maintenant à
//     l'état BLOCKED : conservée, motivée, affichée, rejouable par
//     l'évaluateur (jamais effacée).
//
// Les échecs sont classés, parce qu'ils n'appellent pas la même réaction :
//   • auth       → il faut se reconnecter ; NE consomme PAS d'essai
//   • permanent  → réessayer ne changera rien (400/403/409) → BLOCKED direct
//   • temporaire → réseau / 5xx → on réessaiera plus tard

import 'dart:async';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../../features/grading/domain/repositories/grading_repository.dart';
import 'connectivity_service.dart';
import 'offline_storage_service.dart';

/// Nature d'un échec d'envoi — détermine la réaction, pas seulement le message.
enum SyncFailureKind {
  /// 401 : la session n'est plus valable. Se reconnecter, puis relancer.
  auth,

  /// 400 / 403 / 409 : le serveur refuse et refusera encore. Une personne doit
  /// intervenir (p. ex. « vous n'êtes plus l'évaluateur de cette station »).
  permanent,

  /// Réseau, délai d'attente, 5xx : ça peut marcher tout à l'heure.
  temporary,
}

/// Classe une exception d'envoi. Public pour être testable directement :
/// c'est la décision la plus lourde de conséquences du fichier.
@visibleForTesting
SyncFailureKind classifySyncFailure(Object error) {
  if (error is! DioException) return SyncFailureKind.temporary;

  switch (error.type) {
    case DioExceptionType.connectionError:
    case DioExceptionType.connectionTimeout:
    case DioExceptionType.sendTimeout:
    case DioExceptionType.receiveTimeout:
      return SyncFailureKind.temporary;
    case DioExceptionType.badResponse:
      final code = error.response?.statusCode ?? 0;
      if (code == 401) return SyncFailureKind.auth;
      if (code >= 500) return SyncFailureKind.temporary;
      if (code >= 400) return SyncFailureKind.permanent;
      return SyncFailureKind.temporary;
    default:
      return SyncFailureKind.temporary;
  }
}

/// Extrait le message du serveur — c'est LUI qui explique le refus à
/// l'évaluateur (« Verrouillé », « vous n'êtes pas affecté à cette station »).
@visibleForTesting
String describeSyncFailure(Object error) {
  if (error is DioException) {
    final data = error.response?.data;
    if (data is Map && data['message'] is String) {
      final msg = (data['message'] as String).trim();
      if (msg.isNotEmpty) return msg;
    }
    final code = error.response?.statusCode;
    if (code != null) return 'Le serveur a refusé l\'envoi (code $code).';
  }
  return 'Envoi impossible.';
}

/// Résultat d'une opération de synchronisation.
class SyncResult {
  /// Confirmées par le serveur (2xx) — les seules supprimées du téléphone.
  final int synced;

  /// Échec temporaire : restent en attente, seront retentées.
  final int retryLater;

  /// Bloquées : conservées, avec un motif, en attente de l'évaluateur.
  final int blocked;

  /// La session n'est plus valable — il faut se reconnecter.
  final bool authFailed;

  /// Le réseau est reparti pendant l'envoi : le reste n'a pas été tenté.
  final bool interrupted;

  final DateTime syncedAt;

  const SyncResult({
    required this.synced,
    required this.retryLater,
    required this.blocked,
    required this.syncedAt,
    this.authFailed  = false,
    this.interrupted = false,
  });

  bool get hasFailures =>
      retryLater > 0 || blocked > 0 || authFailed || interrupted;

  bool get isComplete => !hasFailures;

  /// Rien n'a pu partir alors qu'il y avait du travail.
  bool get totalFailure => synced == 0 && hasFailures;
}

class SyncService {
  SyncService._();
  static final SyncService instance = SyncService._();

  static const int _batchSize  = 10;
  static const int _maxRetries = 3;

  GradingRepository? _repository;
  StreamSubscription<bool>? _connectivitySub;

  final _syncResultController   = StreamController<SyncResult>.broadcast();
  final _syncProgressController = StreamController<bool>.broadcast();

  /// Résultats de synchronisation (écouter pour mettre à jour l'UI).
  Stream<SyncResult> get onSyncResult => _syncResultController.stream;

  /// true quand une synchronisation est en cours.
  Stream<bool> get onSyncProgress => _syncProgressController.stream;

  bool _isSyncing = false;

  // ── Points d'injection (tests) ───────────────────────────────────────────

  PendingStore? _storeOverride;
  bool Function()? _isOnlineOverride;

  PendingStore get _store => _storeOverride ?? OfflineStorageService.instance;
  bool get _isOnline =>
      _isOnlineOverride?.call() ?? ConnectivityService.instance.isOnline;

  @visibleForTesting
  void debugConfigure({
    required GradingRepository repository,
    required PendingStore store,
    bool Function()? isOnline,
  }) {
    _repository       = repository;
    _storeOverride    = store;
    _isOnlineOverride = isOnline;
    _isSyncing        = false;
  }

  /// Vrai si `init()` a bien été appelé. #307 — le service était complet mais
  /// personne ne lui passait son repository : `syncNow()` sortait à la
  /// deuxième ligne et AUCUNE note hors ligne n'est jamais remontée.
  bool get isWired => _repository != null;

  // ── Initialisation ───────────────────────────────────────────────────────

  /// Doit être appelé une seule fois au démarrage (voir `main.dart`).
  /// Se branche sur ConnectivityService pour déclencher la sync au retour en ligne.
  void init(GradingRepository repository) {
    _repository = repository;

    _connectivitySub?.cancel();
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
    if (_isSyncing)          return null;
    if (_repository == null) {
      debugPrint('[SyncService] ⛔ non câblé (init non appelé) — sync ignorée');
      return null;
    }

    final pending = await _store.getPendingNotations();
    if (pending.isEmpty) return null;

    _isSyncing = true;
    _syncProgressController.add(true);
    debugPrint('[SyncService] Début sync : ${pending.length} notation(s) en attente');

    int synced      = 0;
    int retryLater  = 0;
    int blocked     = 0;
    bool authFailed  = false;
    bool interrupted = false;

    for (int i = 0; i < pending.length && !authFailed && !interrupted; i += _batchSize) {
      final batch = pending.sublist(
        i,
        (i + _batchSize).clamp(0, pending.length),
      );

      final successIds = <int>[];
      final retryIds   = <int>[];
      final blockedIds = <_BlockedRow>[];

      for (final pn in batch) {
        // Le réseau peut retomber PENDANT la boucle : chaque note est un
        // aller-retour. Sans ce contrôle, la suite du lot partait « hors
        // ligne » et repassait par le repli local.
        if (!_isOnline) {
          interrupted = true;
          break;
        }

        try {
          await _repository!.pushNotation(pn.toNotation());
          // ✅ 2xx : et SEULEMENT ici, la suppression locale est légitime.
          if (pn.id != null) successIds.add(pn.id!);
          synced++;
        } catch (e) {
          final kind = classifySyncFailure(e);
          final why  = describeSyncFailure(e);
          debugPrint('[SyncService] Échec notation id=${pn.id} ($kind) : $why');

          switch (kind) {
            case SyncFailureKind.auth:
              // Ne consomme PAS d'essai : ce n'est pas la note qui est en
              // cause, c'est la session. Et on arrête là — les suivantes
              // échoueraient toutes pareil.
              authFailed = true;
              break;

            case SyncFailureKind.permanent:
              if (pn.id != null) blockedIds.add(_BlockedRow(pn.id!, why));
              blocked++;
              break;

            case SyncFailureKind.temporary:
              if (pn.retryCount + 1 >= _maxRetries) {
                // Plafond atteint : on ARRÊTE de réessayer, on ne SUPPRIME pas.
                if (pn.id != null) {
                  blockedIds.add(_BlockedRow(
                    pn.id!,
                    'Envoi impossible après $_maxRetries tentatives. $why',
                  ));
                }
                blocked++;
              } else {
                if (pn.id != null) retryIds.add(pn.id!);
                retryLater++;
              }
              break;
          }

          if (authFailed) break;
        }
      }

      await _store.deleteByIds(successIds);       // ⛔ confirmées uniquement
      await _store.incrementRetry(retryIds);
      for (final row in blockedIds) {
        await _store.markBlocked([row.id], row.reason);
      }
    }

    await _store.logSync(
      count:   synced + retryLater + blocked,
      success: retryLater == 0 && blocked == 0 && !authFailed && !interrupted,
    );

    final result = SyncResult(
      synced:      synced,
      retryLater:  retryLater,
      blocked:     blocked,
      authFailed:  authFailed,
      interrupted: interrupted,
      syncedAt:    DateTime.now(),
    );

    debugPrint(
      '[SyncService] Fin sync : $synced envoyées, $retryLater à retenter, '
      '$blocked bloquées, auth=$authFailed, interrompue=$interrupted',
    );

    _isSyncing = false;
    _syncProgressController.add(false);
    _syncResultController.add(result);

    return result;
  }

  /// Nombre de notations locales en attente (pour le badge).
  Future<int> getPendingCount() => _store.getPendingCount();

  /// #307 — nombre de notations bloquées (badge rouge, action requise).
  Future<int> getBlockedCount() => _store.getBlockedCount();

  /// #307 — l'évaluateur redemande l'envoi de ses notes bloquées.
  /// Elles repassent en attente, compteur d'essais remis à zéro, puis on
  /// relance immédiatement.
  Future<SyncResult?> retryBlocked() async {
    // ⛔ Hors ligne, on ne débloque PAS. Sinon les notes repasseraient en
    // « en attente », le bandeau rouge « ne sont pas parties » disparaîtrait —
    // et l'évaluateur croirait le problème réglé alors que rien n'a été
    // envoyé. Un avertissement qui s'efface sans que la cause ait bougé est
    // pire que pas d'avertissement.
    if (!_isOnline) {
      debugPrint('[SyncService] Reprise demandée hors ligne — refusée');
      return null;
    }
    final restored = await _store.unblockAll();
    debugPrint('[SyncService] $restored notation(s) débloquée(s) par l\'évaluateur');
    return syncNow();
  }

  // ── Nettoyage ────────────────────────────────────────────────────────────

  void dispose() {
    _connectivitySub?.cancel();
    _syncResultController.close();
    _syncProgressController.close();
  }
}

class _BlockedRow {
  final int    id;
  final String reason;
  const _BlockedRow(this.id, this.reason);
}
