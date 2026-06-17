// lib/features/grading/data/models/grading_models.dart
// ================================================
// Modèles JSON pour toute la feature grading

import '../../domain/entities/grille.dart';
import '../../domain/entities/item_evaluation.dart';
import '../../domain/entities/lot.dart';
import '../../domain/entities/notation.dart';

// ════════════════════════════════════════════
// ItemEvaluationModel
// ════════════════════════════════════════════
class ItemEvaluationModel extends ItemEvaluation {
  const ItemEvaluationModel({
    required super.id,
    required super.libelle,
    required super.type,
    required super.ponderation,
    required super.valeurMax,
    required super.ordre,
    super.categorie,
  });

  /// Réponse JSON de GET /stations/{id}/grille → data.items[]:
  /// {
  ///   "id": 1,
  ///   "libelle": "Choix de l'indicateur coloré",
  ///   "type": "BINAIRE",
  ///   "ponderation": 2.0,
  ///   "valeurMax": null,   ← null pour BINAIRE côté backend (colonne nullable)
  ///   "ordre": 1,
  ///   "categorie": null
  /// }
  factory ItemEvaluationModel.fromJson(Map<String, dynamic> json) {
    final type = _parseType((json['type'] as String? ?? 'BINAIRE'));

    // valeurMax est nullable en base (null pour les items BINAIRE).
    // Pour les items BINAIRE, on utilise 1.0 comme valeur de référence
    // (Fait = 1, Non fait = 0), ce qui correspond à la logique métier.
    final double valeurMax;
    final rawValeurMax = json['valeurMax'];
    if (rawValeurMax != null) {
      valeurMax = (rawValeurMax as num).toDouble();
    } else {
      valeurMax = type == TypeCritere.binaire ? 1.0 : 0.0;
    }

    return ItemEvaluationModel(
      id:          _toInt(json['id']),
      libelle:     json['libelle'] as String? ?? '',
      type:        type,
      ponderation: _toDouble(json['ponderation']),
      valeurMax:   valeurMax,
      ordre:       _toInt(json['ordre']),
      categorie:   json['categorie'] as String?,
    );
  }

  Map<String, dynamic> toJson() => {
    'id':          id,
    'libelle':     libelle,
    'type':        type == TypeCritere.binaire ? 'BINAIRE' : 'NUMERIQUE',
    'ponderation': ponderation,
    'valeurMax':   valeurMax,
    'ordre':       ordre,
    'categorie':   categorie,
  };

  static TypeCritere _parseType(String type) =>
      type.toUpperCase() == 'BINAIRE'
          ? TypeCritere.binaire
          : TypeCritere.numerique;
}

// ════════════════════════════════════════════
// GrilleModel
// ════════════════════════════════════════════
class GrilleModel extends Grille {
  const GrilleModel({
    required super.id,
    required super.nom,
    required super.noteMax,
    required super.items,
  });

  /// Réponse JSON de GET /stations/{id}/grille (enveloppée dans ApiResponse) :
  /// {
  ///   "id": 1,
  ///   "nom": "Grille Station 3 — Titrimétrie",
  ///   "noteMax": 20.0,
  ///   "items": [ {...} ]   ← peut être null si grille vide
  /// }
  factory GrilleModel.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'];
    final items = (rawItems is List ? rawItems : <dynamic>[])
        .map((i) => ItemEvaluationModel.fromJson(i as Map<String, dynamic>))
        .toList()
      ..sort((a, b) => a.ordre.compareTo(b.ordre));

    return GrilleModel(
      id:      _toInt(json['id']),
      nom:     json['nom'] as String? ?? '',
      noteMax: _toDouble(json['noteMax']),
      items:   items,
    );
  }
}

// ════════════════════════════════════════════
// EtudiantModel
// ════════════════════════════════════════════
class EtudiantModel extends Etudiant {
  const EtudiantModel({
    required super.id,
    required super.nom,
    required super.prenom,
    required super.numeroInscription,
    super.numeroEchantillon,
    super.absent,
    super.verrouille,
    super.commentaire,
    super.notationExistante,
  });

  factory EtudiantModel.fromJson(Map<String, dynamic> json) {
    // Construire la map des notations existantes depuis notationItems[]
    final notationExistante = <int, double>{};
    final rawItems = json['notationItems'] as List<dynamic>? ?? [];
    for (final raw in rawItems) {
      final map    = raw as Map<String, dynamic>;
      final itemId = map['itemId'] as int;
      final valeur = (map['valeur'] as num).toDouble();
      notationExistante[itemId] = valeur;
    }

    return EtudiantModel(
      id:                json['id']                as int,
      nom:               json['nom']               as String,
      prenom:            json['prenom']             as String,
      numeroInscription: json['numeroInscription']  as String,
      numeroEchantillon: json['numeroEchantillon']  as int?,
      absent:            json['absent']             as bool? ?? false,
      verrouille:        json['verrouille']         as bool? ?? false,
      commentaire:       json['commentaire']        as String?,
      notationExistante: notationExistante,
    );
  }

  Map<String, dynamic> toJson() => {
    'id':                id,
    'nom':               nom,
    'prenom':            prenom,
    'numeroInscription': numeroInscription,
    'numeroEchantillon': numeroEchantillon,
    'absent':            absent,
    'verrouille':        verrouille,
    'commentaire':       commentaire,
  };

  /// Convertit le numéro d'échantillon qui peut arriver :
  ///   - comme int  : 5
  ///   - comme String : "5"
  ///   - comme null
  static int? _parseEchantillon(dynamic value) {
    if (value == null) return null;
    if (value is int) return value;
    if (value is String) return int.tryParse(value.trim());
    if (value is num) return value.toInt();
    return null;
  }
}

// ════════════════════════════════════════════
// LotModel
// ════════════════════════════════════════════
class LotModel extends Lot {
  const LotModel({
    required super.id,
    required super.numero,
    required super.total,
    required super.etudiants,
    super.valide,
  });

  /// Réponse JSON de GET /evaluateur/stations/{id}/lots/{num} :
  /// {
  ///   "id": 12,
  ///   "numero": 3,
  ///   "total": 8,
  ///   "valide": false,
  ///   "etudiants": [ {...} ]
  /// }
  factory LotModel.fromJson(Map<String, dynamic> json) {
    final rawEtudiants = json['etudiants'];
    final etudiants = (rawEtudiants is List ? rawEtudiants : <dynamic>[])
        .map((e) => EtudiantModel.fromJson(e as Map<String, dynamic>))
        .toList();

    return LotModel(
      id:        _toInt(json['id']),
      numero:    _toInt(json['numero']),
      total:     _toInt(json['total']),
      valide:    json['valide'] as bool? ?? false,
      etudiants: etudiants,
    );
  }
}

// ════════════════════════════════════════════
// NotationModel
// ════════════════════════════════════════════
class NotationModel extends Notation {
  final int?  serverId; // ID côté backend (null si pas encore synchro)
  final bool  synchro;  // true = sauvegardé sur le serveur

  const NotationModel({
    required super.etudiantId,
    required super.itemId,
    required super.valeur,
    super.verrouille,
    this.serverId,
    this.synchro = false,
  });

  factory NotationModel.fromJson(Map<String, dynamic> json) {
    return NotationModel(
      serverId:   json['id'] != null ? _toInt(json['id']) : null,
      etudiantId: _toInt(json['etudiantId']),
      itemId:     _toInt(json['itemId']),
      valeur:     _toDouble(json['valeur']),
      verrouille: json['verrouille'] as bool? ?? false,
      synchro:    true,
    );
  }

  Map<String, dynamic> toJson() => {
    if (serverId != null) 'id': serverId,
    'etudiantId': etudiantId,
    'itemId':     itemId,
    'valeur':     valeur,
    'verrouille': verrouille,
  };

  NotationModel copyWithSynchro({required int serverId}) => NotationModel(
    serverId:   serverId,
    etudiantId: etudiantId,
    itemId:     itemId,
    valeur:     valeur,
    verrouille: verrouille,
    synchro:    true,
  );
}

// ════════════════════════════════════════════
// Helpers de conversion sûre
//
// Java renvoie les entiers longs comme des int JSON standard.
// Dart 2.x parse les entiers JSON en int, mais json_decode peut
// donner un int ou un double selon la valeur (ex: 1 vs 1.0).
// Ces helpers normalisent les deux cas.
// ════════════════════════════════════════════

int _toInt(dynamic value) {
  if (value == null) return 0;
  if (value is int) return value;
  if (value is double) return value.toInt();
  if (value is String) return int.tryParse(value) ?? 0;
  return 0;
}

double _toDouble(dynamic value) {
  if (value == null) return 0.0;
  if (value is double) return value;
  if (value is int) return value.toDouble();
  if (value is String) return double.tryParse(value) ?? 0.0;
  return 0.0;
}