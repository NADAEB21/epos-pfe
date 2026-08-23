// lib/core/offline/offline_storage_service.dart
// ================================================
// BF6.2 — Stockage local des notations en mode hors-ligne.
//
// Implémente une couche SQLite (via sqflite) qui persiste les saisies
// de l'évaluateur quand le réseau est indisponible. Les données sont
// récupérées intactes à la réouverture (CA-4.3 : perte max 5 sec).
//
// Schéma :
//   pending_notations — notations non synchronisées
//   sync_log          — historique des synchronisations (audit)

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';
import 'package:flutter/foundation.dart' show kIsWeb, visibleForTesting;

import '../../features/grading/domain/entities/notation.dart';

/// État d'une notation locale.
///
/// #307 — `blocked` REMPLACE l'ancien « abandon » qui faisait un DELETE. Une
/// note bloquée reste en base locale, indéfiniment : c'est la note d'un
/// étudiant, elle n'existe nulle part ailleurs tant que le serveur ne l'a pas
/// confirmée. Seule une réponse 2xx autorise la suppression.
class PendingStatus {
  static const String pending = 'PENDING';
  static const String blocked = 'BLOCKED';
}

/// offline cache — une entrée de grille mise en cache.
/// `cachedAtMs` n'est pas encore exploité côté UI mais est conservé dès
/// maintenant : le jour où l'app affiche "grille du 14/08 à 09h12", la
/// colonne existe déjà plutôt que d'exiger une nouvelle migration.
class GrilleCacheEntry {
  final String grilleJson;
  final int    cachedAtMs;
  const GrilleCacheEntry({required this.grilleJson, required this.cachedAtMs});
}

/// Représente une notation en attente de synchronisation.
class PendingNotation {
  final int?   id;          // PK locale (null avant insertion)
  final int    etudiantId;
  final int    stationId;
  final int    grilleId;
  final int    itemId;
  final double valeur;
  final int    createdAtMs; // timestamp de saisie (ms epoch)
  final int    retryCount;  // nombre de tentatives d'envoi
  final String status;      // #307 — PENDING | BLOCKED
  final String? lastError;  // #307 — motif lisible du blocage

  const PendingNotation({
    this.id,
    required this.etudiantId,
    required this.stationId,
    required this.grilleId,
    required this.itemId,
    required this.valeur,
    required this.createdAtMs,
    this.retryCount = 0,
    this.status = PendingStatus.pending,
    this.lastError,
  });

  bool get estBloquee => status == PendingStatus.blocked;

  factory PendingNotation.fromNotation(Notation n) {
    assert(n.stationId != null && n.grilleId != null,
        'stationId et grilleId sont requis pour persister hors-ligne');
    return PendingNotation(
      etudiantId:  n.etudiantId,
      stationId:   n.stationId!,
      grilleId:    n.grilleId!,
      itemId:      n.itemId,
      valeur:      n.valeur,
      createdAtMs: DateTime.now().millisecondsSinceEpoch,
    );
  }

  Notation toNotation() => Notation(
    etudiantId: etudiantId,
    itemId:     itemId,
    valeur:     valeur,
    stationId:  stationId,
    grilleId:   grilleId,
  );

  Map<String, dynamic> toMap() => {
    if (id != null) 'id': id,
    'etudiant_id':   etudiantId,
    'station_id':    stationId,
    'grille_id':     grilleId,
    'item_id':       itemId,
    'valeur':        valeur,
    'created_at_ms': createdAtMs,
    'retry_count':   retryCount,
    'status':        status,
    'last_error':    lastError,
  };

  factory PendingNotation.fromMap(Map<String, dynamic> m) => PendingNotation(
    id:          m['id'] as int?,
    etudiantId:  m['etudiant_id']   as int,
    stationId:   m['station_id']    as int,
    grilleId:    m['grille_id']     as int,
    itemId:      m['item_id']       as int,
    valeur:      (m['valeur'] as num).toDouble(),
    createdAtMs: m['created_at_ms'] as int,
    retryCount:  m['retry_count']   as int,
    // Colonnes ajoutées en v2 : une base v1 migrée porte le DEFAULT, mais on
    // reste tolérant si la lecture précède la migration.
    status:      (m['status'] as String?) ?? PendingStatus.pending,
    lastError:   m['last_error'] as String?,
  );

  PendingNotation copyWith({int? retryCount, String? status, String? lastError}) =>
      PendingNotation(
        id:          id,
        etudiantId:  etudiantId,
        stationId:   stationId,
        grilleId:    grilleId,
        itemId:      itemId,
        valeur:      valeur,
        createdAtMs: createdAtMs,
        retryCount:  retryCount ?? this.retryCount,
        status:      status     ?? this.status,
        lastError:   lastError  ?? this.lastError,
      );
}

/// #307 — le sous-ensemble du stockage dont la synchronisation a besoin.
///
/// Existe pour que la boucle de synchronisation soit testable SANS SQLite
/// (qui exige un appareil). Le défaut de suppression a survécu précisément
/// parce qu'aucun test ne pouvait exercer ce chemin.
abstract class PendingStore {
  Future<List<PendingNotation>> getPendingNotations();
  Future<void> deleteByIds(List<int> ids);
  Future<void> incrementRetry(List<int> ids);
  Future<void> markBlocked(List<int> ids, String reason);
  Future<int> getPendingCount();
  Future<int> getBlockedCount();
  Future<int> unblockAll();
  Future<void> logSync({required int count, required bool success});
}

/// Service singleton d'accès à la base SQLite locale.
class OfflineStorageService implements PendingStore {
  OfflineStorageService._();
  static final OfflineStorageService instance = OfflineStorageService._();

  Database? _db;

  static const String _dbName    = 'epos_offline.db';
  // v3 — (#244) : + table grille_cache. Additive, comme v1→v2 :
  // aucune ligne existante n'est touchée.
  static const int    _dbVersion = 3;

  static const String _tableNotations = 'pending_notations';
  static const String _tableSyncLog   = 'sync_log';
  static const String _tableLabels    = 'labels';
  static const String _tableGrilleCache = 'grille_cache';

  /// #307 — chemin de base forcé, pour les tests. Permet d'exercer le VRAI
  /// SQLite (via `sqflite_common_ffi`) sur un fichier temporaire, y compris la
  /// migration v1 → v2, qui autrement ne s'exécuterait pour la première fois
  /// que sur le téléphone d'un évaluateur, le jour d'un examen.
  @visibleForTesting
  static String? debugDbPath;

  /// Referme la base et oublie l'instance ouverte (isolation entre tests).
  @visibleForTesting
  Future<void> debugReset() async {
    await _db?.close();
    _db = null;
  }

  /// Version du schéma, exposée pour que les tests vérifient la migration.
  @visibleForTesting
  static int get schemaVersion => _dbVersion;

  // ── Initialisation ──────────────────────────────────────────────────────

  Future<Database> get _database async {
    _db ??= await _initDatabase();
    return _db!;
  }

  Future<Database> _initDatabase() async {
    final dbPath = debugDbPath ?? p.join(await getDatabasesPath(), _dbName);
    return openDatabase(
      dbPath,
      version:   _dbVersion,
      onCreate:  _onCreate,
      onUpgrade: _onUpgrade,
    );
  }

  /// #307 — migration v1 → v2 sur un téléphone qui porte DÉJÀ des notes.
  /// Purement additive : aucune ligne n'est touchée, les notes existantes
  /// deviennent PENDING (le DEFAULT), donc elles repartent en synchronisation.
  Future<void> _onUpgrade(Database db, int oldVersion, int newVersion) async {
    if (oldVersion < 2) {
      // SQLite ne connaît pas `ADD COLUMN IF NOT EXISTS`. Si la migration est
      // interrompue (l'app est tuée entre deux ALTER), la reprise rejouerait
      // le premier ALTER et échouerait sur « duplicate column name » — la base
      // deviendrait alors inouvrable, donc les notes seraient INACCESSIBLES.
      // Chaque étape est donc rendue idempotente.
      await _ajouterColonneSiAbsente(
        db,
        "ALTER TABLE $_tableNotations ADD COLUMN status TEXT NOT NULL DEFAULT '${PendingStatus.pending}'",
      );
      await _ajouterColonneSiAbsente(
        db, 'ALTER TABLE $_tableNotations ADD COLUMN last_error TEXT',
      );
      await _ajouterColonneSiAbsente(
        db, 'ALTER TABLE $_tableNotations ADD COLUMN last_attempt_ms INTEGER',
      );
      await db.execute('''
        CREATE TABLE IF NOT EXISTS $_tableLabels (
          kind    TEXT    NOT NULL,
          ref_id  INTEGER NOT NULL,
          label   TEXT    NOT NULL,
          PRIMARY KEY (kind, ref_id)
        )
      ''');
    }
    // v2→v3 (#244) — table de cache de grille. CREATE TABLE IF NOT EXISTS :
    // même précaution qu'ailleurs dans cette méthode si une migration
    // partielle a déjà tourné une fois.
    if (oldVersion < 3) {
      await db.execute('''
        CREATE TABLE IF NOT EXISTS $_tableGrilleCache (
          station_id    INTEGER PRIMARY KEY,
          grille_json   TEXT    NOT NULL,
          cached_at_ms  INTEGER NOT NULL
        )
      ''');
    }
  }

  /// Rejoue un ALTER sans casser si la colonne existe déjà. On n'avale QUE ce
  /// cas précis : toute autre erreur doit remonter.
  Future<void> _ajouterColonneSiAbsente(Database db, String sql) async {
    try {
      await db.execute(sql);
    } on DatabaseException catch (e) {
      // Vérifié en test : sans ce rattrapage, la reprise échoue sur
      // « duplicate column name: status » et la base devient inouvrable.
      if (!e.toString().toLowerCase().contains('duplicate column')) rethrow;
    }
  }

  Future<void> _onCreate(Database db, int version) async {
    await db.execute('''
      CREATE TABLE $_tableNotations (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        etudiant_id   INTEGER NOT NULL,
        station_id    INTEGER NOT NULL,
        grille_id     INTEGER NOT NULL,
        item_id       INTEGER NOT NULL,
        valeur        REAL    NOT NULL,
        created_at_ms INTEGER NOT NULL,
        retry_count   INTEGER NOT NULL DEFAULT 0,
        status        TEXT    NOT NULL DEFAULT '${PendingStatus.pending}',
        last_error    TEXT,
        last_attempt_ms INTEGER
      )
    ''');

    // #307 — libellés lisibles (étudiant, station), écrits au chargement d'un
    // lot. Sans eux, l'écran des notes bloquées ne pourrait afficher que des
    // numéros — or il doit rester lisible HORS LIGNE, donc on ne peut pas
    // aller chercher les noms au moment de l'affichage.
    await db.execute('''
      CREATE TABLE $_tableLabels (
        kind    TEXT    NOT NULL,
        ref_id  INTEGER NOT NULL,
        label   TEXT    NOT NULL,
        PRIMARY KEY (kind, ref_id)
      )
    ''');

    await db.execute('''
      CREATE TABLE $_tableSyncLog (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        synced_at_ms  INTEGER NOT NULL,
        count         INTEGER NOT NULL,
        success       INTEGER NOT NULL
      )
    ''');

    // (#244) — installation neuve : la table existe dès le départ.
    await db.execute('''
      CREATE TABLE $_tableGrilleCache (
        station_id    INTEGER PRIMARY KEY,
        grille_json   TEXT    NOT NULL,
        cached_at_ms  INTEGER NOT NULL
      )
    ''');

    // Index pour accélerer les requêtes de sync (etudiant × station × item)
    await db.execute('''
      CREATE INDEX idx_pending_etudiant_item
      ON $_tableNotations (etudiant_id, station_id, item_id)
    ''');
  }

  // ── Cache de grille (#244) ───────────────────────────────────────────────
  //
  // Best-effort, upsert par station_id : la dernière grille reçue avec succès
  // remplace la précédente. Pas de TTL — une grille est gelée dès EN_COURS
  // (ADR-0015), donc la fraîcheur du cache n'est jamais un enjeu de
  // correction, seulement de disponibilité pendant une coupure.

  /// Écrit (ou remplace) la grille mise en cache pour une station.
  Future<void> cacheGrille(int stationId, String grilleJson) async {
    if (kIsWeb) return;
    final db = await _database;
    await db.insert(
      _tableGrilleCache,
      {
        'station_id':   stationId,
        'grille_json':  grilleJson,
        'cached_at_ms': DateTime.now().millisecondsSinceEpoch,
      },
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  /// Dernière grille mise en cache pour cette station, ou `null` — soit
  /// qu'elle n'a jamais été ouverte avec succès, soit que l'app tourne sur
  /// le web (sqflite désactivé, même repli que le reste de ce service).
  Future<GrilleCacheEntry?> getCachedGrille(int stationId) async {
    if (kIsWeb) return null;
    final db   = await _database;
    final rows = await db.query(
      _tableGrilleCache,
      where:     'station_id = ?',
      whereArgs: [stationId],
      limit:     1,
    );
    if (rows.isEmpty) return null;
    final row = rows.first;
    return GrilleCacheEntry(
      grilleJson: row['grille_json'] as String,
      cachedAtMs: row['cached_at_ms'] as int,
    );
  }

  // ── Écriture ────────────────────────────────────────────────────────────

  /// Insère ou remplace une notation en attente.
  /// Si (etudiantId, stationId, itemId) existe déjà → met à jour la valeur
  /// (upsert : la dernière saisie gagne, cohérent avec BF6.3).
  Future<void> upsertNotation(PendingNotation notation) async {
    // sqflite n'est pas supporté nativement sur navigateur (nécessiterait
    // sqflite_common_ffi_web). Sur Chrome, on désactive silencieusement la
    // persistance locale : les notations passent par le chemin "en ligne" de
    // GradingRepositoryImpl, donc rien n'est perdu tant qu'on est connecté.
    // Le vrai mode hors-ligne doit être testé sur émulateur/appareil Android.
    if (kIsWeb) return;

    final db = await _database;

    // Cherche une entrée existante pour cet item
    final existing = await db.query(
      _tableNotations,
      where: 'etudiant_id = ? AND station_id = ? AND item_id = ?',
      whereArgs: [notation.etudiantId, notation.stationId, notation.itemId],
      limit: 1,
    );

    if (existing.isEmpty) {
      await db.insert(_tableNotations, notation.toMap());
    } else {
      final existingId = existing.first['id'] as int;
      await db.update(
        _tableNotations,
        {
          'valeur':        notation.valeur,
          'created_at_ms': notation.createdAtMs,
          'retry_count':   0, // reset les retries lors d'une nouvelle saisie
          // #307 — une nouvelle saisie DÉBLOQUE la ligne : l'évaluateur vient
          // de retaper la note, elle doit repartir en synchronisation.
          'status':        PendingStatus.pending,
          'last_error':    null,
        },
        where: 'id = ?',
        whereArgs: [existingId],
      );
    }
  }

  // ── Lecture ─────────────────────────────────────────────────────────────

  /// Notations à ENVOYER. Exclut les bloquées : elles attendent une action de
  /// l'évaluateur, les renvoyer en boucle ne ferait que répéter le même échec.
  @override
  Future<List<PendingNotation>> getPendingNotations() async {
    if (kIsWeb) return [];
    final db   = await _database;
    final rows = await db.query(
      _tableNotations,
      where:     'status = ?',
      whereArgs: [PendingStatus.pending],
      orderBy:   'created_at_ms ASC',
    );
    return rows.map(PendingNotation.fromMap).toList();
  }

  /// #307 — notations BLOQUÉES : conservées, jamais supprimées, en attente
  /// d'une reprise par l'évaluateur.
  Future<List<PendingNotation>> getBlockedNotations() async {
    if (kIsWeb) return [];
    final db   = await _database;
    final rows = await db.query(
      _tableNotations,
      where:     'status = ?',
      whereArgs: [PendingStatus.blocked],
      orderBy:   'created_at_ms ASC',
    );
    return rows.map(PendingNotation.fromMap).toList();
  }

  /// Nombre de notations bloquées (badge rouge).
  @override
  Future<int> getBlockedCount() async {
    if (kIsWeb) return 0;
    final db     = await _database;
    final result = await db.rawQuery(
      'SELECT COUNT(*) as cnt FROM $_tableNotations WHERE status = ?',
      [PendingStatus.blocked],
    );
    return (result.first['cnt'] as int?) ?? 0;
  }

  /// #307 — marque des notations comme bloquées. REMPLACE l'ancien DELETE :
  /// la note reste en base, avec le motif, jusqu'à ce qu'elle parte.
  @override
  Future<void> markBlocked(List<int> ids, String reason) async {
    if (kIsWeb) return;
    if (ids.isEmpty) return;
    final db           = await _database;
    final placeholders = List.filled(ids.length, '?').join(',');
    await db.rawUpdate(
      'UPDATE $_tableNotations SET status = ?, last_error = ?, last_attempt_ms = ? '
      'WHERE id IN ($placeholders)',
      [PendingStatus.blocked, reason, DateTime.now().millisecondsSinceEpoch, ...ids],
    );
  }

  /// #307 — l'évaluateur redemande l'envoi : tout repasse en attente et le
  /// compteur d'essais repart de zéro.
  @override
  Future<int> unblockAll() async {
    if (kIsWeb) return 0;
    final db = await _database;
    return db.rawUpdate(
      'UPDATE $_tableNotations SET status = ?, retry_count = 0, last_error = NULL '
      'WHERE status = ?',
      [PendingStatus.pending, PendingStatus.blocked],
    );
  }

  /// Nombre de notations en attente d'envoi (badge UI) — hors bloquées.
  @override
  Future<int> getPendingCount() async {
    if (kIsWeb) return 0;
    final db     = await _database;
    final result = await db.rawQuery(
      'SELECT COUNT(*) as cnt FROM $_tableNotations WHERE status = ?',
      [PendingStatus.pending],
    );
    return (result.first['cnt'] as int?) ?? 0;
  }

  // ── Suppression ─────────────────────────────────────────────────────────

  /// Supprime des notations.
  ///
  /// ⛔ #307 — N'APPELER QU'APRÈS UNE CONFIRMATION 2xx DU SERVEUR. Une note
  /// supprimée n'existe plus nulle part : le téléphone était son seul
  /// dépositaire. L'ancien code appelait ceci après un « échec avalé » traité
  /// comme un succès, et après 3 essais ratés — dans les deux cas la note de
  /// l'étudiant disparaissait en silence.
  @override
  Future<void> deleteByIds(List<int> ids) async {
    if (kIsWeb) return;
    if (ids.isEmpty) return;
    final db          = await _database;
    final placeholders = List.filled(ids.length, '?').join(',');
    await db.delete(
      _tableNotations,
      where: 'id IN ($placeholders)',
      whereArgs: ids,
    );
  }

  /// Incrémente le compteur de retry pour les notations en échec.
  @override
  Future<void> incrementRetry(List<int> ids) async {
    if (kIsWeb) return;
    if (ids.isEmpty) return;
    final db           = await _database;
    final placeholders = List.filled(ids.length, '?').join(',');
    await db.rawUpdate(
      'UPDATE $_tableNotations SET retry_count = retry_count + 1 WHERE id IN ($placeholders)',
      ids,
    );
  }

  /// Vide toutes les données locales (à utiliser avec précaution).
  Future<void> clearAll() async {
    if (kIsWeb) return;
    final db = await _database;
    await db.delete(_tableNotations);
    await db.delete(_tableSyncLog);
    await db.delete(_tableLabels);
    await db.delete(_tableGrilleCache); // (#244) le cache de grille part avec le reste
  }

  // ── Libellés lisibles (#307) ─────────────────────────────────────────────

  static const String kindEtudiant = 'etudiant';
  static const String kindStation  = 'station';

  /// Mémorise « 12 → Sonia Karoui » pour que l'écran des notes bloquées parle
  /// de personnes et non de numéros, MÊME hors ligne.
  Future<void> rememberLabel(String kind, int refId, String label) async {
    if (kIsWeb) return;
    if (label.trim().isEmpty) return;
    final db = await _database;
    await db.insert(
      _tableLabels,
      {'kind': kind, 'ref_id': refId, 'label': label},
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
  }

  /// Tous les libellés d'un type, en une requête (l'écran en affiche plusieurs).
  Future<Map<int, String>> getLabels(String kind) async {
    if (kIsWeb) return {};
    final db   = await _database;
    final rows = await db.query(
      _tableLabels,
      where:     'kind = ?',
      whereArgs: [kind],
    );
    return {
      for (final r in rows) r['ref_id'] as int: r['label'] as String,
    };
  }

  // ── Journal de synchronisation ───────────────────────────────────────────

  @override
  Future<void> logSync({
    required int  count,
    required bool success,
  }) async {
    if (kIsWeb) return;
    final db = await _database;
    await db.insert(_tableSyncLog, {
      'synced_at_ms': DateTime.now().millisecondsSinceEpoch,
      'count':        count,
      'success':      success ? 1 : 0,
    });
  }

  // ── Fermeture ────────────────────────────────────────────────────────────

  Future<void> close() async {
    if (kIsWeb) return;
    await _db?.close();
    _db = null;
  }
}