// lib/core/theme/app_theme.dart
// ================================================
// Thème EPOS correspondant au design Figma
// Couleurs primaires : vert foncé (#2D5016 / #4A7C2F), fond crème (#F5F0E8)

import 'package:flutter/material.dart';

class AppTheme {
  AppTheme._();

  // === Palette EPOS (extraite du Figma) ===
  static const Color primary         = Color(0xFF3D6B1E);   // Vert foncé
  static const Color primaryDark     = Color(0xFF2D5016);   // Vert très foncé (AppBar)
  static const Color primaryLight    = Color(0xFF6A9E42);   // Vert clair
  static const Color accent          = Color(0xFFB8860B);   // Or/Doré (boutons secondaires)
  static const Color background      = Color(0xFFF5F0E8);   // Fond crème
  static const Color surface         = Color(0xFFFFFFFF);
  static const Color cardBackground  = Color(0xFFF9F6F0);

  // === Couleurs sémantiques ===
  static const Color scoreGreen      = Color(0xFF4CAF50);   // ≥ 14/20
  static const Color scoreOrange     = Color(0xFFFF9800);   // 10–14/20
  static const Color scoreRed        = Color(0xFFF44336);   // < 10/20

  static const Color criterionDone   = Color(0xFF4A7C2F);   // ✓ Fait
  static const Color criterionNotDone= Color(0xFFE53935);   // ✗ Non fait
  static const Color criterionPending= Color(0xFFBDBDBD);   // Non saisi

  // === Texte ===
  static const Color textPrimary     = Color(0xFF1C1C1C);
  static const Color textSecondary   = Color(0xFF5C5C5C);
  static const Color textOnPrimary   = Color(0xFFFFFFFF);

  static ThemeData get lightTheme => ThemeData(
    useMaterial3: true,
    colorScheme: ColorScheme.fromSeed(
      seedColor: primary,
      primary: primary,
      onPrimary: textOnPrimary,
      secondary: accent,
      // FIX: 'background' déprécié depuis Flutter 3.18 → utiliser 'surface'
      surface: background,
    ),
    scaffoldBackgroundColor: background,

    // === AppBar ===
    appBarTheme: const AppBarTheme(
      backgroundColor: primaryDark,
      foregroundColor: textOnPrimary,
      elevation: 0,
      centerTitle: false,
      titleTextStyle: TextStyle(
        color: textOnPrimary,
        fontSize: 18,
        fontWeight: FontWeight.w600,
        fontFamily: 'Poppins',
      ),
    ),

    // === Cartes ===
    // FIX: CardTheme → CardThemeData (renommé depuis Flutter 3.19)
    cardTheme: CardThemeData(
      color: cardBackground,
      elevation: 2,
      shadowColor: Colors.black12,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
      ),
    ),

    // === Boutons primaires ===
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: primary,
        foregroundColor: textOnPrimary,
        minimumSize: const Size(double.infinity, 52),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
        ),
        textStyle: const TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.w600,
          fontFamily: 'Poppins',
        ),
      ),
    ),

    // === Champs de saisie ===
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: surface,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: Color(0xFFDDD8CC)),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: Color(0xFFDDD8CC)),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide: const BorderSide(color: primary, width: 2),
      ),
      labelStyle: const TextStyle(color: textSecondary),
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
    ),

    // === Typography ===
    textTheme: const TextTheme(
      headlineLarge: TextStyle(
        fontSize: 28, fontWeight: FontWeight.w700,
        color: textPrimary, fontFamily: 'Poppins',
      ),
      headlineMedium: TextStyle(
        fontSize: 22, fontWeight: FontWeight.w600,
        color: textPrimary, fontFamily: 'Poppins',
      ),
      titleLarge: TextStyle(
        fontSize: 18, fontWeight: FontWeight.w600,
        color: textPrimary, fontFamily: 'Poppins',
      ),
      titleMedium: TextStyle(
        fontSize: 16, fontWeight: FontWeight.w500,
        color: textPrimary, fontFamily: 'Poppins',
      ),
      bodyLarge: TextStyle(
        fontSize: 15, fontWeight: FontWeight.w400,
        color: textPrimary, fontFamily: 'Poppins',
      ),
      bodyMedium: TextStyle(
        fontSize: 13, fontWeight: FontWeight.w400,
        color: textSecondary, fontFamily: 'Poppins',
      ),
    ),
  );

  /// Retourne la couleur correspondant au score /20
  static Color scoreColor(double score) {
    if (score >= 14) return scoreGreen;
    if (score >= 10) return scoreOrange;
    return scoreRed;
  }
}