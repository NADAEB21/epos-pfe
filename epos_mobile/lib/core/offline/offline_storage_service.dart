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

import '../../features/grading/domain/entities/notation.dart';

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

  const PendingNotation({
    this.id,
    required this.etudiantId,
    required this.stationId,
    required this.grilleId,
    required this.itemId,
    required this.valeur,
    required this.createdAtMs,
    this.retryCount = 0,
  });

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
  );

  PendingNotation copyWith({int? retryCount}) => PendingNotation(
    id:          id,
    etudiantId:  etudiantId,
    stationId:   stationId,
    grilleId:    grilleId,
    itemId:      itemId,
    valeur:      valeur,
    createdAtMs: createdAtMs,
    retryCount:  retryCount ?? this.retryCount,
  );
}

/// Service singleton d'accès à la base SQLite locale.
class OfflineStorageService {
  OfflineStorageService._();
  static final OfflineStorageService instance = OfflineStorageService._();

  Database? _db;

  static const String _dbName    = 'epos_offline.db';
  static const int    _dbVersion = 1;

  static const String _tableNotations = 'pending_notations';
  static const String _tableSyncLog   = 'sync_log';

  // ── Initialisation ──────────────────────────────────────────────────────

  Future<Database> get _database async {
    _db ??= await _initDatabase();
    return _db!;
  }

  Future<Database> _initDatabase() async {
    final dbPath = p.join(await getDatabasesPath(), _dbName);
    return openDatabase(
      dbPath,
      version: _dbVersion,
      onCreate: _onCreate,
    );
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
        retry_count   INTEGER NOT NULL DEFAULT 0
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

    // Index pour accélerer les requêtes de sync (etudiant × station × item)
    await db.execute('''
      CREATE INDEX idx_pending_etudiant_item
      ON $_tableNotations (etudiant_id, station_id, item_id)
    ''');
  }

  // ── Écriture ────────────────────────────────────────────────────────────

  /// Insère ou remplace une notation en attente.
  /// Si (etudiantId, stationId, itemId) existe déjà → met à jour la valeur
  /// (upsert : la dernière saisie gagne, cohérent avec BF6.3).
  Future<void> upsertNotation(PendingNotation notation) async {
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
        },
        where: 'id = ?',
        whereArgs: [existingId],
      );
    }
  }

  // ── Lecture ─────────────────────────────────────────────────────────────

  /// Retourne toutes les notations en attente de synchronisation.
  Future<List<PendingNotation>> getPendingNotations() async {
    final db   = await _database;
    final rows = await db.query(
      _tableNotations,
      orderBy: 'created_at_ms ASC',
    );
    return rows.map(PendingNotation.fromMap).toList();
  }

  /// Nombre de notations en attente (pour le badge UI).
  Future<int> getPendingCount() async {
    final db     = await _database;
    final result = await db.rawQuery(
      'SELECT COUNT(*) as cnt FROM $_tableNotations',
    );
    return (result.first['cnt'] as int?) ?? 0;
  }

  // ── Suppression ─────────────────────────────────────────────────────────

  /// Supprime les notations synchronisées avec succès.
  Future<void> deleteByIds(List<int> ids) async {
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
  Future<void> incrementRetry(List<int> ids) async {
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
    final db = await _database;
    await db.delete(_tableNotations);
    await db.delete(_tableSyncLog);
  }

  // ── Journal de synchronisation ───────────────────────────────────────────

  Future<void> logSync({
    required int  count,
    required bool success,
  }) async {
    final db = await _database;
    await db.insert(_tableSyncLog, {
      'synced_at_ms': DateTime.now().millisecondsSinceEpoch,
      'count':        count,
      'success':      success ? 1 : 0,
    });
  }

  // ── Fermeture ────────────────────────────────────────────────────────────

  Future<void> close() async {
    await _db?.close();
    _db = null;
  }
}