// test/unit/offline_storage_migration_test.dart
// ================================================
// #307 — LE VRAI SQLite, pas une doublure.
//
// La limite assumée de la PR #308 : la couche de stockage et sa migration
// v1 → v2 n'avaient JAMAIS été exécutées. `sqflite` n'existe pas sur le web,
// et le code s'y désactive — donc la première exécution réelle aurait eu lieu
// sur le téléphone d'un évaluateur, le jour d'un examen, sur une base
// contenant de vraies notes.
//
// `sqflite_common_ffi` exécute le VRAI moteur SQLite dans un test Dart. Ces
// tests ouvrent donc une base v1 authentique, contenant des notes, et la font
// migrer pour de bon.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:epos_mobile/core/offline/offline_storage_service.dart';

/// DDL EXACT de la v1 (avant #307) — recopié de l'historique, pas réinventé :
/// un test de migration qui part d'un schéma imaginaire ne prouve rien.
const _ddlV1Notations = '''
  CREATE TABLE pending_notations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    etudiant_id   INTEGER NOT NULL,
    station_id    INTEGER NOT NULL,
    grille_id     INTEGER NOT NULL,
    item_id       INTEGER NOT NULL,
    valeur        REAL    NOT NULL,
    created_at_ms INTEGER NOT NULL,
    retry_count   INTEGER NOT NULL DEFAULT 0
  )
''';

const _ddlV1SyncLog = '''
  CREATE TABLE sync_log (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    synced_at_ms INTEGER NOT NULL,
    count        INTEGER NOT NULL,
    success      INTEGER NOT NULL
  )
''';

late Directory _tmp;

String _newDbPath(String name) => '${_tmp.path}${Platform.pathSeparator}$name';

/// Crée une base v1 CONTENANT des notes — le cas qui compte : un téléphone
/// déjà utilisé, pas une installation neuve.
Future<void> _creerBaseV1AvecNotes(String path, {int nombre = 2}) async {
  final db = await databaseFactory.openDatabase(
    path,
    options: OpenDatabaseOptions(
      version: 1,
      onCreate: (db, _) async {
        await db.execute(_ddlV1Notations);
        await db.execute(_ddlV1SyncLog);
        await db.execute(
          'CREATE INDEX idx_pending_etudiant_item '
          'ON pending_notations (etudiant_id, station_id, item_id)',
        );
      },
    ),
  );
  for (var i = 1; i <= nombre; i++) {
    await db.insert('pending_notations', {
      'etudiant_id':   40 + i,
      'station_id':    2,
      'grille_id':     9,
      'item_id':       i,
      'valeur':        i.toDouble(),
      'created_at_ms': 1700000000000 + i,
      'retry_count':   0,
    });
  }
  await db.close();
}

void main() {
  setUpAll(() {
    sqfliteFfiInit();
    databaseFactory = databaseFactoryFfi;
    _tmp = Directory.systemTemp.createTempSync('epos_offline_test');
  });

  tearDown(() async {
    await OfflineStorageService.instance.debugReset();
    OfflineStorageService.debugDbPath = null;
  });

  tearDownAll(() {
    try {
      _tmp.deleteSync(recursive: true);
    } catch (_) {/* Windows garde parfois un verrou : sans importance */}
  });

  group('#307 — migration v1 → v2 sur un téléphone qui porte DÉJÀ des notes', () {
    test('les notes existantes SURVIVENT et repartent en attente', () async {
      final path = _newDbPath('migration_ok.db');
      await _creerBaseV1AvecNotes(path, nombre: 3);

      OfflineStorageService.debugDbPath = path;
      final service = OfflineStorageService.instance;

      // La première lecture ouvre la base → déclenche onUpgrade.
      final pending = await service.getPendingNotations();

      expect(pending.length, 3,
          reason: 'AUCUNE note ne doit être perdue par la migration');
      expect(pending.every((n) => n.status == PendingStatus.pending), isTrue);
      expect(pending.every((n) => n.lastError == null), isTrue);
      // Les valeurs saisies sont intactes, pas seulement le nombre de lignes.
      expect(pending.map((n) => n.valeur).toList(), [1.0, 2.0, 3.0]);
      expect(pending.map((n) => n.etudiantId).toList(), [41, 42, 43]);
    });

    test('la table des libellés est créée par la migration', () async {
      final path = _newDbPath('migration_labels.db');
      await _creerBaseV1AvecNotes(path);

      OfflineStorageService.debugDbPath = path;
      final service = OfflineStorageService.instance;

      await service.rememberLabel(
          OfflineStorageService.kindEtudiant, 41, 'Sonia Karoui');
      final labels =
          await service.getLabels(OfflineStorageService.kindEtudiant);

      expect(labels[41], 'Sonia Karoui');
    });

    test('MIGRATION INTERROMPUE : la reprise ne casse pas la base', () async {
      // Le cas trouvé pendant la passe adversariale. SQLite n'a pas de
      // « ADD COLUMN IF NOT EXISTS » : si l'app est tuée après le premier
      // ALTER, la reprise le rejouait et échouait sur « duplicate column ».
      // La base devenait alors INOUVRABLE — donc toutes les notes
      // inaccessibles. On simule exactement ça.
      final path = _newDbPath('migration_interrompue.db');
      await _creerBaseV1AvecNotes(path, nombre: 2);

      final brut = await databaseFactory.openDatabase(path);
      await brut.execute(
        "ALTER TABLE pending_notations ADD COLUMN status TEXT NOT NULL "
        "DEFAULT '${PendingStatus.pending}'",
      );
      // user_version reste à 1 : la migration sera rejouée en entier.
      await brut.close();

      OfflineStorageService.debugDbPath = path;
      final service = OfflineStorageService.instance;

      final pending = await service.getPendingNotations();

      expect(pending.length, 2,
          reason: 'la reprise doit aboutir, et les notes rester lisibles');
      expect(pending.first.status, PendingStatus.pending);
    });
  });

  group('#307 — comportement réel du stockage (vrai SQL)', () {
    late OfflineStorageService service;

    setUp(() async {
      OfflineStorageService.debugDbPath =
          _newDbPath('frais_${DateTime.now().microsecondsSinceEpoch}.db');
      service = OfflineStorageService.instance;
    });

    Future<void> semer({int n = 3}) async {
      for (var i = 1; i <= n; i++) {
        await service.upsertNotation(PendingNotation(
          etudiantId:  40 + i,
          stationId:   2,
          grilleId:    9,
          itemId:      i,
          valeur:      i.toDouble(),
          createdAtMs: 1700000000000 + i,
        ));
      }
    }

    test('installation neuve : le schéma v3 est créé directement', () async {
      await semer(n: 1);
      final rows = await service.getPendingNotations();
      expect(rows.single.status, PendingStatus.pending);
      expect(OfflineStorageService.schemaVersion, 3);
    });

    test('une note bloquée SORT de la file d\'envoi mais RESTE en base',
        () async {
      await semer();
      final rows = await service.getPendingNotations();
      await service.markBlocked([rows.first.id!], 'Verrouillé côté serveur');

      expect((await service.getPendingNotations()).length, 2,
          reason: 'la bloquée ne doit plus être renvoyée en boucle');
      expect(await service.getPendingCount(), 2);
      expect(await service.getBlockedCount(), 1);

      final bloquees = await service.getBlockedNotations();
      expect(bloquees.single.lastError, 'Verrouillé côté serveur');
      expect(bloquees.single.valeur, 1.0,
          reason: 'la VALEUR de la note est conservée, c\'est tout l\'objet');
    });

    test('« Réessayer » remet les bloquées en attente, essais remis à zéro',
        () async {
      await semer(n: 2);
      final rows = await service.getPendingNotations();
      await service.incrementRetry([rows.first.id!]);
      await service.markBlocked([rows.first.id!], 'panne');

      final restaurees = await service.unblockAll();

      expect(restaurees, 1);
      expect(await service.getBlockedCount(), 0);
      final apres = await service.getPendingNotations();
      expect(apres.length, 2);
      expect(apres.firstWhere((n) => n.id == rows.first.id).retryCount, 0);
    });

    test('resaisir une note bloquée la débloque', () async {
      await semer(n: 1);
      final row = (await service.getPendingNotations()).single;
      await service.markBlocked([row.id!], 'refus serveur');
      expect(await service.getBlockedCount(), 1);

      // L'évaluateur retape la note : même (étudiant, station, item).
      await service.upsertNotation(PendingNotation(
        etudiantId:  row.etudiantId,
        stationId:   row.stationId,
        grilleId:    row.grilleId,
        itemId:      row.itemId,
        valeur:      2.0,
        createdAtMs: 1800000000000,
      ));

      expect(await service.getBlockedCount(), 0);
      final apres = await service.getPendingNotations();
      expect(apres.single.valeur, 2.0);
      expect(apres.single.lastError, isNull);
    });

    test('deleteByIds ne supprime QUE les identifiants fournis', () async {
      await semer();
      final rows = await service.getPendingNotations();
      await service.deleteByIds([rows.first.id!]);

      final restantes = await service.getPendingNotations();
      expect(restantes.length, 2);
      expect(restantes.any((n) => n.id == rows.first.id), isFalse);
    });

    test('un libellé réécrit remplace l\'ancien, sans doublon', () async {
      await service.rememberLabel(OfflineStorageService.kindStation, 2, 'Station 2');
      await service.rememberLabel(
          OfflineStorageService.kindStation, 2, 'Station 2 — Galénique');

      final labels = await service.getLabels(OfflineStorageService.kindStation);
      expect(labels.length, 1);
      expect(labels[2], 'Station 2 — Galénique');
    });
  });

  group('#244 — cache de grille (Phase 2 offline)', () {
    late OfflineStorageService service;

    setUp(() async {
      OfflineStorageService.debugDbPath =
          _newDbPath('grille_${DateTime.now().microsecondsSinceEpoch}.db');
      service = OfflineStorageService.instance;
    });

    test('aucune entrée pour une station jamais mise en cache', () async {
      expect(await service.getCachedGrille(999), isNull);
    });

    test('cacheGrille puis getCachedGrille renvoie le même JSON', () async {
      const json = '{"id":5,"nom":"Titrimétrie","noteMax":20.0,"items":[]}';
      await service.cacheGrille(5, json);

      final cached = await service.getCachedGrille(5);
      expect(cached, isNotNull);
      expect(cached!.grilleJson, json);
      expect(cached.cachedAtMs, greaterThan(0));
    });

    test('un second appel REMPLACE l\'entrée (upsert par station)', () async {
      await service.cacheGrille(5, '{"nom":"v1"}');
      await service.cacheGrille(5, '{"nom":"v2"}');

      final cached = await service.getCachedGrille(5);
      expect(cached!.grilleJson, '{"nom":"v2"}');
    });

    test('deux stations différentes ne se marchent pas dessus', () async {
      await service.cacheGrille(5, '{"nom":"station5"}');
      await service.cacheGrille(9, '{"nom":"station9"}');

      expect((await service.getCachedGrille(5))!.grilleJson, contains('station5'));
      expect((await service.getCachedGrille(9))!.grilleJson, contains('station9'));
    });

    test('la migration v1→v3 crée la table grille_cache sans perdre les notes',
            () async {
          // Réutilise le même scénario que le groupe "migration v1 → v2" plus haut :
          // une base v1 authentique avec des notes doit ressortir en v3, notes
          // intactes ET nouvelle table disponible.
          final path = _newDbPath('migration_v1_vers_v3.db');
          await _creerBaseV1AvecNotes(path, nombre: 2);

          OfflineStorageService.debugDbPath = path;
          final svc = OfflineStorageService.instance;

          final pending = await svc.getPendingNotations();
          expect(pending.length, 2, reason: 'les notes v1 doivent survivre à v1→v3');

          await svc.cacheGrille(1, '{"nom":"après migration"}');
          expect((await svc.getCachedGrille(1))!.grilleJson, contains('après migration'));
        });
  });
}
