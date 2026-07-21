// lib/features/grading/domain/entities/lot.dart

import 'package:equatable/equatable.dart';
import 'notation.dart';

class Lot extends Equatable {
  final int           id;
  final int           numero;        // ex: 3 (Lot 3/8)
  final int           total;         // ex: 8
  final List<Etudiant> etudiants;
  final bool          valide;

  /// #248 — reste-t-il un passage après celui-ci, à CETTE station et dans CE lot ?
  ///
  /// Vient du backend, qui le calcule sur `ordrePassage`. À ne JAMAIS redéduire de
  /// `numero`/`total` : le carré latin fait tourner les groupes, donc une station peut
  /// recevoir le groupe 2 puis le groupe 1 — « je suis le groupe K » ne signifie pas
  /// « je suis le dernier passage ». C'est cette confusion qui grisait le bouton au
  /// premier passage et l'activait au dernier.
  final bool groupeSuivantDisponible;

  const Lot({
    required this.id,
    required this.numero,
    required this.total,
    required this.etudiants,
    this.valide = false,
    this.groupeSuivantDisponible = false,
  });

  String get label => 'Lot $numero/$total';

  @override
  List<Object?> get props =>
      [id, numero, total, etudiants, valide, groupeSuivantDisponible];
}