// lib/features/grading/domain/entities/item_evaluation.dart

import 'package:equatable/equatable.dart';

enum TypeCritere { binaire, numerique }

class ItemEvaluation extends Equatable {
  final int         id;
  final String      libelle;
  final TypeCritere type;
  final double      ponderation;
  final double      valeurMax;
  final int         ordre;
  final String?     categorie;

  const ItemEvaluation({
    required this.id,
    required this.libelle,
    required this.type,
    required this.ponderation,
    required this.valeurMax,
    required this.ordre,
    this.categorie,
  });

  @override
  List<Object?> get props =>
      [id, libelle, type, ponderation, valeurMax, ordre, categorie];
}