// lib/features/grading/data/repositories/mock_grading_repository.dart
// ================================================
// Données fictives calquées sur le Figma :
//   Station 3 — Titrimétrie
//   Lot 3/8 · 4 étudiants
//   Grille complète du cahier des charges (8 items, 20 pts)

import '../../domain/entities/grille.dart';
import '../../domain/entities/item_evaluation.dart';
import '../../domain/entities/lot.dart';
import '../../domain/entities/notation.dart';
import '../../domain/repositories/grading_repository.dart';
import '../models/grading_models.dart';

class MockGradingRepository implements GradingRepository {
  static const _delay = Duration(milliseconds: 600);

  // Notations en mémoire (simule la DB locale)
  final Map<String, NotationModel> _notations = {};

  // ── Grille Station 3 — Titrimétrie (cahier des charges §3.3.1) ──
  static const _grilleStation3 = GrilleModel(
    id:      1,
    nom:     'Station 3 — Dosage par titrimétrie (Vitamine C)',
    noteMax: 20,
    items: [
      ItemEvaluationModel(
        id: 1, ordre: 1,
        libelle:     '1a — Choix de l\'indicateur coloré',
        type:        TypeCritere.binaire,
        ponderation: 2, valeurMax: 1,
        categorie:   'Préparation',
      ),
      ItemEvaluationModel(
        id: 2, ordre: 2,
        libelle:     '1b — Vérification du titre de NaOH 0.1 N',
        type:        TypeCritere.binaire,
        ponderation: 1, valeurMax: 1,
        categorie:   'Préparation',
      ),
      ItemEvaluationModel(
        id: 3, ordre: 3,
        libelle:     '2 — Utilisation du bon indicateur (phénolphtaléine)',
        type:        TypeCritere.binaire,
        ponderation: 2, valeurMax: 1,
        categorie:   'Manipulation',
      ),
      ItemEvaluationModel(
        id: 4, ordre: 4,
        libelle:     '3 — Utilisation correcte de la burette',
        type:        TypeCritere.binaire,
        ponderation: 3, valeurMax: 1,
        categorie:   'Manipulation',
      ),
      ItemEvaluationModel(
        id: 5, ordre: 5,
        libelle:     '4 — Prise du volume lors du virage',
        type:        TypeCritere.binaire,
        ponderation: 2, valeurMax: 1,
        categorie:   'Manipulation',
      ),
      ItemEvaluationModel(
        id: 6, ordre: 6,
        libelle:     '5 — Propreté de manipulation',
        type:        TypeCritere.binaire,
        ponderation: 2, valeurMax: 1,
        categorie:   'Propreté',
      ),
      ItemEvaluationModel(
        id: 7, ordre: 7,
        libelle:     '6 — Calcul de la masse (démarche + résultat)',
        type:        TypeCritere.numerique,
        ponderation: 6, valeurMax: 6,
        categorie:   'Calcul',
      ),
      ItemEvaluationModel(
        id: 8, ordre: 8,
        libelle:     '7 — Lavage du matériel et rinçage de la burette',
        type:        TypeCritere.binaire,
        ponderation: 2, valeurMax: 1,
        categorie:   'Propreté',
      ),
    ],
  );

  // ── 6 étudiants du Lot 3 ────────────────────────────────────
  static const _etudiants = [
    EtudiantModel(
      id: 101, nom: 'Bousselmi', prenom: 'Feten',
      numeroInscription: '21/0042', numeroEchantillon: 5,
    ),
    EtudiantModel(
      id: 102, nom: 'Mansour', prenom: 'Youssef',
      numeroInscription: '21/0067', numeroEchantillon: 12,
    ),
    EtudiantModel(
      id: 103, nom: 'Trabelsi', prenom: 'Amira',
      numeroInscription: '21/0091', numeroEchantillon: 3,
    ),
    EtudiantModel(
      id: 104, nom: 'Gharbi', prenom: 'Sami',
      numeroInscription: '21/0115', numeroEchantillon: 8,
    ),
    EtudiantModel(
      id: 105, nom: 'Ebdeli', prenom: 'Nada',
      numeroInscription: '21/0116', numeroEchantillon: 6,
    ),
    EtudiantModel(
      id: 106, nom: 'Bennour', prenom: 'Asma',
      numeroInscription: '21/0117', numeroEchantillon: 4,
    ),
  ];

  // Notations pré-remplies pour simuler une session en cours
  // (Feten : 15/20 comme dans le Figma)
  final Map<String, NotationModel> _initialNotations = {
    '101-1': const NotationModel(etudiantId: 101, itemId: 1, valeur: 1),
    '101-2': const NotationModel(etudiantId: 101, itemId: 2, valeur: 1),
    '101-3': const NotationModel(etudiantId: 101, itemId: 3, valeur: 1),
    '101-4': const NotationModel(etudiantId: 101, itemId: 4, valeur: 1),
    '101-5': const NotationModel(etudiantId: 101, itemId: 5, valeur: 1),
    '101-6': const NotationModel(etudiantId: 101, itemId: 6, valeur: 0),
    '101-7': const NotationModel(etudiantId: 101, itemId: 7, valeur: 3),
    // Mansour : quelques notes
    '102-1': const NotationModel(etudiantId: 102, itemId: 1, valeur: 1),
    '102-3': const NotationModel(etudiantId: 102, itemId: 3, valeur: 1),
    '102-4': const NotationModel(etudiantId: 102, itemId: 4, valeur: 1),
  };

  MockGradingRepository() {
    _notations.addAll(_initialNotations);
  }

  @override
  Future<Grille> getGrille(int stationId) async {
    await Future.delayed(_delay);
    // Station 3 → retourne la grille Titrimétrie
    // (on pourrait ajouter les variantes Station 4 ici)
    return _grilleStation3;
  }

  @override
  Future<Lot> getGroupe(int rotationId) async {
    await Future.delayed(_delay);
    return LotModel(id: rotationId, numero: 1, total: 4, etudiants: _etudiants, valide: false);
  }

  @override
  Future<void> saveNotation(Notation notation) async {
    await Future.delayed(const Duration(milliseconds: 100));
    final key = '${notation.etudiantId}-${notation.itemId}';
    _notations[key] = NotationModel(
      etudiantId: notation.etudiantId,
      itemId:     notation.itemId,
      valeur:     notation.valeur,
      verrouille: notation.verrouille,
      synchro:    true,
    );
  }

  /// #307 — en mode maquette l'envoi réussit toujours ; il n'y a pas de réseau
  /// à échouer. Même effet que `saveNotation`.
  @override
  Future<void> pushNotation(Notation notation) => saveNotation(notation);

  @override
  Future<void> saveNotations(List<Notation> notations) async {
    for (final n in notations) {
      await saveNotation(n);
    }
  }

  @override
  Future<void> validerEtudiant(
  int etudiantId,
  int stationId, {
  required int grilleId,
  bool    absent      = false,
  String? commentaire,
}) async {
  await Future.delayed(const Duration(milliseconds: 200));
  if (absent) {
    _notations.removeWhere((key, _) => key.startsWith('$etudiantId-'));
  } else {
    final keys = _notations.keys
        .where((k) => k.startsWith('$etudiantId-'))
        .toList();
    for (final key in keys) {
      final n = _notations[key]!;
      _notations[key] = NotationModel(
        etudiantId: n.etudiantId,
        itemId:     n.itemId,
        valeur:     n.valeur,
        verrouille: true,
        synchro:    n.synchro,
      );
    }
  }
}

  @override
  Future<void> validerGroupe(int rotationId) async {
    await Future.delayed(const Duration(milliseconds: 300));
  }

  @override
  Future<Lot> getGroupeSuivant(int rotationId) async {
    await Future.delayed(_delay);
    return LotModel(id: rotationId + 1, numero: 2, total: 4, etudiants: _etudiants, valide: false);
  }

  @override
  Future<Etudiant> substituerEtudiant({
    required int lotId,
    required int etudiantAbsentId,
    required int etudiantRemplacantId,
  }) async {
    await Future.delayed(const Duration(milliseconds: 400));
    // Retourne un étudiant de substitution fictif
    return const EtudiantModel(
      id:                99,
      nom:               'Ben Salah',
      prenom:            'Rim',
      numeroInscription: '21/0200',
      numeroEchantillon: 15,
    );
  }

  @override
  Future<List<Notation>> getNotationsNonSynchro() async {
    return _notations.values
        .where((n) => !n.synchro)
        .toList();
  }

  @override
  Future<void> marquerSynchro(List<int> notationIds) async {
    // Mock : rien à faire
  }

  // Accès aux notations en mémoire (utilisé par le BLoC)
  Map<int, Map<int, Notation>> getNotationsMap() {
    final result = <int, Map<int, Notation>>{};
    for (final entry in _notations.entries) {
      final n = entry.value;
      result.putIfAbsent(n.etudiantId, () => {})[n.itemId] = n;
    }
    return result;
  }
}