// lib/features/grading/domain/entities/grille.dart
// ================================================
// Entités du domaine de notation — couche domain pure

import 'package:equatable/equatable.dart';
import 'item_evaluation.dart';

class Grille extends Equatable {
  final int                id;
  final String             nom;
  final double             noteMax;
  final List<ItemEvaluation> items;

  const Grille({
    required this.id,
    required this.nom,
    required this.noteMax,
    required this.items,
  });

  @override
  List<Object?> get props => [id, nom, noteMax, items];
}