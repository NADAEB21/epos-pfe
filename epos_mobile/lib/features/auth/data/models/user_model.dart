// lib/features/auth/data/models/user_model.dart

import '../../domain/entities/user.dart';

class UserModel extends User {
  const UserModel({
    required super.id,
    required super.email,
    required super.nom,
    required super.prenom,
    required super.role,
    super.roles,
  });

  /// Parse la réponse JSON de GET /api/v1/auth/me.
  ///
  /// Le backend retourne le champ "role" avec les valeurs exactes de RoleType.java :
  ///   "SUPER_ADMIN"         → UserRole.administrateur
  ///   "RESPONSABLE_MATIERE" → UserRole.responsable
  ///   "EVALUATEUR"          → UserRole.evaluateur
  ///
  /// Un compte peut cumuler plusieurs rôles (un responsable de matière évalue
  /// souvent lui-même) : "roles" porte la liste complète, "role" n'est que le
  /// plus privilégié. "roles" est absent des backends antérieurs — on retombe
  /// alors sur le rôle unique.
  factory UserModel.fromJson(Map<String, dynamic> json) {
    final rolesJson = json['roles'] as List<dynamic>?;

    return UserModel(
      id:     json['id'] as int,
      email:  json['email'] as String,
      nom:    json['nom'] as String,
      prenom: json['prenom'] as String,
      role:   _parseRole(json['role'] as String),
      roles:  rolesJson == null
          ? const []
          : rolesJson
              .map((r) => _parseRole((r as Map<String, dynamic>)['role'] as String))
              .toList(),
    );
  }

  Map<String, dynamic> toJson() => {
    'id':     id,
    'email':  email,
    'nom':    nom,
    'prenom': prenom,
    'role':   role.name.toUpperCase(),
    'roles':  roles.map((r) => {'role': r.name.toUpperCase()}).toList(),
  };

  /// Correction décalage B : mapping complet des valeurs de RoleType.java.
  static UserRole _parseRole(String role) {
    switch (role.toUpperCase()) {
      case 'SUPER_ADMIN':
      case 'ADMIN':
      case 'ADMINISTRATEUR':
        return UserRole.administrateur;
      case 'RESPONSABLE_MATIERE':
      case 'RESPONSABLE':
        return UserRole.responsable;
      case 'EVALUATEUR':
      default:
        return UserRole.evaluateur;
    }
  }
}