// lib/features/grading/domain/entities/lot.dart

import 'package:equatable/equatable.dart';
import 'notation.dart';

class Lot extends Equatable {
  final int           id;
  final int           numero;        // ex: 3 (Lot 3/8)
  final int           total;         // ex: 8
  final List<Etudiant> etudiants;
  final bool          valide;

  const Lot({
    required this.id,
    required this.numero,
    required this.total,
    required this.etudiants,
    this.valide = false,
  });

  String get label => 'Lot $numero/$total';

  @override
  List<Object?> get props => [id, numero, total, etudiants, valide];
}