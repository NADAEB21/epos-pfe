// lib/features/auth/domain/entities/user.dart

import 'package:equatable/equatable.dart';

enum UserRole { administrateur, responsable, evaluateur }

class User extends Equatable {
  final int      id;
  final String   email;
  final String   nom;
  final String   prenom;

  /// Rôle principal (le plus privilégié). Ne décrit PAS l'utilisateur à lui seul :
  /// un responsable de matière évalue souvent lui-même. Pour savoir ce que
  /// l'utilisateur a le droit de faire, passer par [roles] / [estEvaluateur].
  final UserRole role;

  /// Tous les rôles portés par le compte. Vide si le backend est antérieur au
  /// champ "roles" de /auth/me — [tousLesRoles] retombe alors sur [role].
  final List<UserRole> roles;

  const User({
    required this.id,
    required this.email,
    required this.nom,
    required this.prenom,
    required this.role,
    this.roles = const [],
  });

  String get nomComplet  => '$prenom $nom';
  String get initiales   => '${prenom[0]}${nom[0]}'.toUpperCase();

  List<UserRole> get tousLesRoles => roles.isEmpty ? [role] : roles;

  /// Un responsable de matière qui évalue aussi est un évaluateur légitime :
  /// tester le cumul, jamais l'égalité sur [role].
  bool get estEvaluateur  => tousLesRoles.contains(UserRole.evaluateur);
  bool get estResponsable => tousLesRoles.contains(UserRole.responsable);

  static String libelleRole(UserRole r) {
    switch (r) {
      case UserRole.administrateur: return 'Administrateur';
      case UserRole.responsable:    return 'Responsable de matière';
      case UserRole.evaluateur:     return 'Évaluateur';
    }
  }

  String get displayRole => libelleRole(role);

  /// Tous les rôles, pour l'écran profil : « Responsable de matière · Évaluateur ».
  String get displayRoles => tousLesRoles.map(libelleRole).join(' · ');

  @override
  List<Object?> get props => [id, email, nom, prenom, role, roles];
}