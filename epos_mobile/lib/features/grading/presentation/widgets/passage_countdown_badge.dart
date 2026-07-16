// lib/features/grading/presentation/widgets/passage_countdown_badge.dart
// ================================================
// #196 (ADR-0012) — Avertissement de fin de passage + compte à rebours.
//
// Conçu comme un widget PUR (aucun Timer interne, aucun état caché) : il ne
// fait qu'afficher ce qu'on lui passe (tempsRestant, avertissementLeadSec,
// enPause). Le rafraîchissement "chaque seconde" vient du GradingTimerTick
// déjà émis par GradingBloc (voir grading_bloc.dart) qui reconstruit l'écran.
//
// C'est un choix délibéré : un Timer.periodic *interne* à un widget est la
// cause la plus fréquente de `flutter test` qui "hang" (pumpAndSettle()
// n'atteint jamais un état stable tant qu'un timer continue de programmer des
// frames). En gardant ce widget purement dérivé des props, le widget test
// (voir passage_countdown_badge_test.dart) n'a besoin que de tester() { }
// avec des pump() explicites — jamais de pumpAndSettle() qui pourrait
// boucler indéfiniment.

import 'package:flutter/material.dart';

import '../../../../core/theme/app_theme.dart';

/// Résultat du calcul d'affichage du compte à rebours — exposé séparément de
/// la construction du widget pour rester facilement testable unitairement
/// (pas besoin de monter un widget pour vérifier la logique de seuil).
class PassageCountdownStatus {
  /// Le compte à rebours est arrivé dans la fenêtre de préavis
  /// (0 < tempsRestant ≤ avertissementLeadSec) et l'examen n'est pas en pause.
  final bool avertissementActif;

  /// Le passage est dépassé (tempsRestant ≤ 0).
  final bool depasse;

  const PassageCountdownStatus({
    required this.avertissementActif,
    required this.depasse,
  });

  factory PassageCountdownStatus.compute({
    required Duration? tempsRestant,
    required int avertissementLeadSec,
    required bool enPause,
  }) {
    if (tempsRestant == null) {
      return const PassageCountdownStatus(avertissementActif: false, depasse: false);
    }
    final secondes = tempsRestant.inSeconds;
    final depasse  = secondes <= 0;

    // ADR-0009 : jamais d'avertissement pendant une pause — l'horloge est
    // gelée, "le temps qui reste" n'a alors aucun sens opérationnel.
    final avertissementActif = !enPause &&
        !depasse &&
        avertissementLeadSec > 0 &&
        secondes <= avertissementLeadSec;

    return PassageCountdownStatus(
      avertissementActif: avertissementActif,
      depasse: depasse,
    );
  }
}

/// Badge de minuterie affiché dans l'AppBar de l'écran de notation.
/// Remplace l'ancien `_TimerBadge` (grading_screen.dart) en ajoutant l'état
/// "avertissement" (ADR-0012) et l'état "en pause" (ADR-0009).
class PassageCountdownBadge extends StatelessWidget {
  final Duration? tempsRestant;
  final int       avertissementLeadSec;
  final bool      enPause;

  const PassageCountdownBadge({
    super.key,
    required this.tempsRestant,
    this.avertissementLeadSec = 0,
    this.enPause = false,
  });

  @override
  Widget build(BuildContext context) {
    if (enPause) {
      return const _Badge(
        icon: Icons.pause_circle_outline,
        label: 'En pause',
        color: Colors.white70,
      );
    }

    if (tempsRestant == null) return const SizedBox.shrink();

    final status = PassageCountdownStatus.compute(
      tempsRestant: tempsRestant,
      avertissementLeadSec: avertissementLeadSec,
      enPause: enPause,
    );

    final aff = tempsRestant!.abs();
    final mm  = aff.inMinutes.remainder(60).toString().padLeft(2, '0');
    final ss  = aff.inSeconds.remainder(60).toString().padLeft(2, '0');

    final Color color = status.depasse
        ? AppTheme.scoreRed
        : status.avertissementActif
        ? AppTheme.scoreOrange
        : Colors.white;

    return _Badge(
      icon: status.depasse
          ? Icons.timer_off
          : status.avertissementActif
          ? Icons.notifications_active_outlined
          : Icons.timer,
      label: '${status.depasse ? "+" : ""}$mm:$ss',
      color: color,
      pulse: status.avertissementActif,
    );
  }
}

class _Badge extends StatelessWidget {
  final IconData icon;
  final String   label;
  final Color    color;
  final bool     pulse;

  const _Badge({
    required this.icon,
    required this.label,
    required this.color,
    this.pulse = false,
  });

  @override
  Widget build(BuildContext context) {
    final content = Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color:        color.withOpacity(0.2),
        borderRadius: BorderRadius.circular(20),
        border:       Border.all(color: color.withOpacity(0.5)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 14, color: color),
          const SizedBox(width: 5),
          Text(
            label,
            style: TextStyle(
              color:        color,
              fontSize:     13,
              fontWeight:   FontWeight.bold,
              fontFeatures: const [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );

    // L'animation de pulsation reste purement visuelle et locale à ce widget
    // (AnimationController géré par Flutter, pas un Timer applicatif) : elle
    // ne bloque jamais `flutter test`, qui peut la piloter avec des pump()
    // explicites (voir le test associé).
    if (!pulse) return content;
    return _Pulsing(child: content);
  }
}

class _Pulsing extends StatefulWidget {
  final Widget child;
  const _Pulsing({required this.child});

  @override
  State<_Pulsing> createState() => _PulsingState();
}

class _PulsingState extends State<_Pulsing> with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double>   _anim;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 700),
    )..repeat(reverse: true);
    _anim = Tween<double>(begin: 0.55, end: 1.0)
        .animate(CurvedAnimation(parent: _ctrl, curve: Curves.easeInOut));
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _anim,
      builder: (_, child) => Opacity(opacity: _anim.value, child: child),
      child: widget.child,
    );
  }
}

/// Bannière pleine-largeur affichée sous l'AppBar quand l'avertissement de
/// fin de passage est actif — plus visible qu'un simple badge pour un
/// évaluateur qui a "les mains prises" pendant la manipulation.
class PassageWarningBanner extends StatelessWidget {
  final Duration? tempsRestant;
  final int       avertissementLeadSec;
  final bool      enPause;

  const PassageWarningBanner({
    super.key,
    required this.tempsRestant,
    this.avertissementLeadSec = 0,
    this.enPause = false,
  });

  @override
  Widget build(BuildContext context) {
    final status = PassageCountdownStatus.compute(
      tempsRestant: tempsRestant,
      avertissementLeadSec: avertissementLeadSec,
      enPause: enPause,
    );

    if (!status.avertissementActif) return const SizedBox.shrink();

    final secondesRestantes = tempsRestant!.inSeconds;

    return Material(
      color: Colors.transparent,
      child: Container(
        width: double.infinity,
        color: AppTheme.scoreOrange,
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
        child: Row(
          children: [
            const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 16),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                'Fin de passage dans ${secondesRestantes}s — préparez la rotation',
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 12,
                  fontFamily: 'Poppins',
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}