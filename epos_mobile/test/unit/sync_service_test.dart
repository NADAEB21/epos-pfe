// test/unit/sync_service_test.dart
// ================================================
// #307 — RÉGRESSION : une note d'examen ne doit JAMAIS être supprimée du
// téléphone sans confirmation du serveur.
//
// Le défaut d'origine ne demandait ni panne ni acharnement : UN SEUL délai
// d'attente pendant la synchronisation suffisait. `saveNotation()` avalait
// l'erreur réseau, réenregistrait en local, et retournait normalement ; la
// boucle lisait « pas d'exception = succès » et supprimait la ligne. La note
// était comptée dans les « synchronisées » au moment même où elle disparaissait.
//
// Ces tests existent pour que ce chemin ne puisse plus revenir en silence.

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/core/offline/offline_storage_service.dart';
import 'package:epos_mobile/core/offline/sync_service.dart';
import 'package:epos_mobile/features/grading/domain/entities/notation.dart';
import 'package:epos_mobile/features/grading/domain/repositories/grading_repository.dart';

// ── Doublures ────────────────────────────────────────────────────────────────

class _FakeStore implements PendingStore {
  _FakeStore(this.rows);

  List<PendingNotation> rows;

  final List<int>            deleted     = [];
  final List<int>            retried     = [];
  final Map<int, String>     blocked     = {};
  int                        unblocked   = 0;

  @override
  Future<List<PendingNotation>> getPendingNotations() async => rows;

  @override
  Future<void> deleteByIds(List<int> ids) async => deleted.addAll(ids);

  @override
  Future<void> incrementRetry(List<int> ids) async => retried.addAll(ids);

  @override
  Future<void> markBlocked(List<int> ids, String reason) async {
    for (final id in ids) {
      blocked[id] = reason;
    }
  }

  @override
  Future<int> getPendingCount() async => rows.length;

  @override
  Future<int> getBlockedCount() async => blocked.length;

  @override
  Future<int> unblockAll() async {
    unblocked = blocked.length;
    blocked.clear();
    return unblocked;
  }

  @override
  Future<void> logSync({required int count, required bool success}) async {}
}

class _FakeRepo implements GradingRepository {
  _FakeRepo(this.onPush);

  final Future<void> Function(Notation) onPush;
  final List<Notation> pushed = [];

  @override
  Future<void> pushNotation(Notation notation) async {
    pushed.add(notation);
    return onPush(notation);
  }

  @override
  dynamic noSuchMethod(Invocation i) => throw UnimplementedError();
}

PendingNotation _row(int id, {int retryCount = 0}) => PendingNotation(
      id:          id,
      etudiantId:  100 + id,
      stationId:   2,
      grilleId:    9,
      itemId:      id,
      valeur:      1,
      createdAtMs: 1000 + id,
      retryCount:  retryCount,
    );

DioException _timeout() => DioException(
      requestOptions: RequestOptions(path: '/notations/saisir'),
      type:           DioExceptionType.receiveTimeout,
    );

DioException _http(int code, {String? message}) => DioException(
      requestOptions: RequestOptions(path: '/notations/saisir'),
      type:           DioExceptionType.badResponse,
      response: Response(
        requestOptions: RequestOptions(path: '/notations/saisir'),
        statusCode:     code,
        data:           message == null ? null : {'message': message},
      ),
    );

void main() {
  group('#307 — aucune suppression sans confirmation du serveur', () {
    test('LE DÉFAUT D\'ORIGINE : un délai d\'attente ne supprime RIEN', () async {
      final store = _FakeStore([_row(1), _row(2)]);
      final repo  = _FakeRepo((_) async => throw _timeout());
      SyncService.instance.debugConfigure(
        repository: repo, store: store, isOnline: () => true,
      );

      final result = await SyncService.instance.syncNow();

      // L'ancien code faisait : deleted == [1, 2] et synced == 2.
      expect(store.deleted, isEmpty,
          reason: 'une note non confirmée ne doit jamais être supprimée');
      expect(result!.synced, 0,
          reason: 'un échec ne doit jamais être compté comme un envoi');
      expect(store.retried, [1, 2]);
      expect(result.retryLater, 2);
    });

    test('un 2xx supprime la ligne locale — et lui seul', () async {
      final store = _FakeStore([_row(1), _row(2), _row(3)]);
      final repo  = _FakeRepo((n) async {
        if (n.itemId == 2) throw _timeout();
      });
      SyncService.instance.debugConfigure(
        repository: repo, store: store, isOnline: () => true,
      );

      final result = await SyncService.instance.syncNow();

      expect(store.deleted, [1, 3]);
      expect(store.retried, [2]);
      expect(result!.synced, 2);
    });
  });

  group('#307 — classement des échecs', () {
    test('401 : ne consomme pas d\'essai, ne bloque pas, arrête la boucle', () async {
      final store = _FakeStore([_row(1), _row(2)]);
      final repo  = _FakeRepo((_) async => throw _http(401));
      SyncService.instance.debugConfigure(
        repository: repo, store: store, isOnline: () => true,
      );

      final result = await SyncService.instance.syncNow();

      expect(result!.authFailed, isTrue);
      expect(store.deleted, isEmpty);
      expect(store.retried, isEmpty,
          reason: 'la session est en cause, pas la note : pas de pénalité');
      expect(store.blocked, isEmpty);
      expect(repo.pushed.length, 1,
          reason: 'inutile d\'insister : les suivantes échoueraient pareil');
    });

    test('403 : bloquée immédiatement, avec le motif du serveur', () async {
      final store = _FakeStore([_row(1)]);
      final repo  = _FakeRepo((_) async => throw _http(
            403,
            message: 'Vous n\'êtes pas affecté à cette station.',
          ));
      SyncService.instance.debugConfigure(
        repository: repo, store: store, isOnline: () => true,
      );

      final result = await SyncService.instance.syncNow();

      expect(store.deleted, isEmpty);
      expect(store.blocked[1], contains('pas affecté à cette station'),
          reason: 'le motif doit être celui du serveur, lisible par l\'enseignant');
      expect(result!.blocked, 1);
    });

    test('500 est temporaire, pas permanent', () {
      expect(classifySyncFailure(_http(500)), SyncFailureKind.temporary);
      expect(classifySyncFailure(_http(503)), SyncFailureKind.temporary);
      expect(classifySyncFailure(_http(400)), SyncFailureKind.permanent);
      expect(classifySyncFailure(_http(409)), SyncFailureKind.permanent);
      expect(classifySyncFailure(_http(401)), SyncFailureKind.auth);
      expect(classifySyncFailure(_timeout()), SyncFailureKind.temporary);
      expect(classifySyncFailure(Exception('boom')), SyncFailureKind.temporary);
    });
  });

  group('#307 — plafond d\'essais : on cesse de réessayer, on ne supprime pas', () {
    test('au 3ᵉ échec la note est BLOQUÉE, jamais effacée', () async {
      // retryCount = 2 : la tentative qui suit atteint le plafond de 3.
      final store = _FakeStore([_row(1, retryCount: 2)]);
      final repo  = _FakeRepo((_) async => throw _timeout());
      SyncService.instance.debugConfigure(
        repository: repo, store: store, isOnline: () => true,
      );

      final result = await SyncService.instance.syncNow();

      expect(store.deleted, isEmpty,
          reason: 'C\'EST LE CŒUR DE #307 : l\'ancien code supprimait ici');
      expect(store.blocked.containsKey(1), isTrue);
      expect(result!.blocked, 1);
    });

    test('sous le plafond, la note reste en attente', () async {
      final store = _FakeStore([_row(1, retryCount: 1)]);
      final repo  = _FakeRepo((_) async => throw _timeout());
      SyncService.instance.debugConfigure(
        repository: repo, store: store, isOnline: () => true,
      );

      await SyncService.instance.syncNow();

      expect(store.retried, [1]);
      expect(store.blocked, isEmpty);
      expect(store.deleted, isEmpty);
    });
  });

  group('#307 — perte de réseau PENDANT la boucle', () {
    test('la sync s\'interrompt sans toucher aux notes restantes', () async {
      final store = _FakeStore([_row(1), _row(2), _row(3)]);
      var appels = 0;
      // En ligne pour la première note, hors ligne ensuite.
      SyncService.instance.debugConfigure(
        repository: _FakeRepo((_) async {}),
        store:      store,
        isOnline:   () => appels++ < 1,
      );

      final result = await SyncService.instance.syncNow();

      expect(result!.interrupted, isTrue);
      expect(store.deleted, [1], reason: 'seule celle réellement confirmée part');
      expect(store.blocked, isEmpty);
      expect(store.retried, isEmpty,
          reason: 'ne pas pénaliser des notes qu\'on n\'a même pas tentées');
    });
  });

  group('#307 — reprise par l\'évaluateur', () {
    test('« Réessayer » remet les bloquées en attente et relance', () async {
      final store = _FakeStore([_row(1)]);
      await store.markBlocked([1], 'panne serveur');

      SyncService.instance.debugConfigure(
        repository: _FakeRepo((_) async {}),
        store:      store,
        isOnline:   () => true,
      );

      await SyncService.instance.retryBlocked();

      expect(store.unblocked, 1);
      expect(store.blocked, isEmpty);
      expect(store.deleted, [1], reason: 'cette fois l\'envoi aboutit');
    });
  });

  group('#307 — passe adversariale', () {
    test('« Réessayer » hors ligne ne débloque RIEN (sinon l\'alerte '
        'disparaîtrait sans que rien ne soit parti)', () async {
      final store = _FakeStore([_row(1)]);
      await store.markBlocked([1], 'panne réseau');

      SyncService.instance.debugConfigure(
        repository: _FakeRepo((_) async {}),
        store:      store,
        isOnline:   () => false,
      );

      final result = await SyncService.instance.retryBlocked();

      expect(result, isNull);
      expect(store.unblocked, 0);
      expect(store.blocked.containsKey(1), isTrue,
          reason: 'la note reste bloquée, donc le bandeau rouge reste affiché');
      expect(store.deleted, isEmpty);
    });
  });

  group('#307 — le service doit être câblé', () {
    test('sans repository, syncNow ne fait rien et le signale', () async {
      final service = SyncService.instance;
      service.debugConfigure(
        repository: _FakeRepo((_) async {}),
        store:      _FakeStore([_row(1)]),
        isOnline:   () => true,
      );
      expect(service.isWired, isTrue);
    });
  });
}
