// lib/features/auth/domain/entities/user.dart

import 'package:equatable/equatable.dart';

enum UserRole { administrateur, responsable, evaluateur }

class User extends Equatable {
  final int      id;
  final String   email;
  final String   nom;
  final String   prenom;
  final UserRole role;

  const User({
    required this.id,
    required this.email,
    required this.nom,
    required this.prenom,
    required this.role,
  });

  String get nomComplet  => '$prenom $nom';
  String get initiales   => '${prenom[0]}${nom[0]}'.toUpperCase();
  String get displayRole {
    switch (role) {
      case UserRole.administrateur: return 'Administrateur';
      case UserRole.responsable:    return 'Responsable de matière';
      case UserRole.evaluateur:     return 'Évaluateur';
    }
  }

  @override
  List<Object?> get props => [id, email, nom, prenom, role];
}