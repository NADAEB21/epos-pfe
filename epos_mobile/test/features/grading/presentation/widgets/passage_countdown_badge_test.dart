// test/features/grading/presentation/widgets/passage_countdown_badge_test.dart
//
// #196 — Le ticket signale explicitement un widget test qui "hang" sur la
// branche WIP. Sans accès à ce test (il n'a jamais été poussé), voici un test
// neuf pour le widget de remplacement, écrit pour ÉLIMINER la cause la plus
// fréquente de hang en Flutter widget testing :
//
//   `await tester.pumpAndSettle()` sur un écran qui contient un
//   `Timer.periodic` ou une `AnimationController` en boucle infinie
//   (`..repeat()`) ne se termine JAMAIS, car pumpAndSettle() attend qu'il n'y
//   ait plus AUCUNE frame planifiée — ce qui n'arrive jamais avec une
//   animation répétée à l'infini (voir `_Pulsing` dans
//   passage_countdown_badge.dart, utilisé pour l'état "avertissement actif").
//
// Règle appliquée ici : JAMAIS de pumpAndSettle() sur ces widgets. On avance
// le temps explicitement avec `tester.pump(Duration(...))`, un nombre de fois
// borné, ce qui donne un test déterministe et rapide.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/grading/presentation/widgets/passage_countdown_badge.dart';

void main() {
  group('PassageCountdownStatus.compute (logique pure, sans widget)', () {
    test('aucun avertissement tant que le délai de préavis n\'est pas atteint', () {
      final status = PassageCountdownStatus.compute(
        tempsRestant: const Duration(minutes: 5),
        avertissementLeadSec: 120,
        enPause: false,
      );
      expect(status.avertissementActif, isFalse);
      expect(status.depasse, isFalse);
    });

    test('avertissement actif dans la fenêtre de préavis', () {
      final status = PassageCountdownStatus.compute(
        tempsRestant: const Duration(seconds: 90),
        avertissementLeadSec: 120,
        enPause: false,
      );
      expect(status.avertissementActif, isTrue);
      expect(status.depasse, isFalse);
    });

    test('jamais d\'avertissement pendant une pause (ADR-0009)', () {
      final status = PassageCountdownStatus.compute(
        tempsRestant: const Duration(seconds: 30),
        avertissementLeadSec: 120,
        enPause: true,
      );
      expect(status.avertissementActif, isFalse);
    });

    test('avertissementLeadSec = 0 désactive complètement la fonctionnalité', () {
      final status = PassageCountdownStatus.compute(
        tempsRestant: const Duration(seconds: 5),
        avertissementLeadSec: 0,
        enPause: false,
      );
      expect(status.avertissementActif, isFalse);
    });

    test('passage dépassé', () {
      final status = PassageCountdownStatus.compute(
        tempsRestant: const Duration(seconds: -3),
        avertissementLeadSec: 120,
        enPause: false,
      );
      expect(status.depasse, isTrue);
      expect(status.avertissementActif, isFalse);
    });

    test('tempsRestant null → aucun état actif', () {
      final status = PassageCountdownStatus.compute(
        tempsRestant: null,
        avertissementLeadSec: 120,
        enPause: false,
      );
      expect(status.avertissementActif, isFalse);
      expect(status.depasse, isFalse);
    });
  });

  group('PassageCountdownBadge (widget)', () {
    testWidgets('affiche "En pause" quand enPause est vrai', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: PassageCountdownBadge(
            tempsRestant: Duration(minutes: 2),
            avertissementLeadSec: 120,
            enPause: true,
          ),
        ),
      ));

      // Un seul pump() : ce widget n'a pas d'animation dans son état "pause".
      await tester.pump();

      expect(find.text('En pause'), findsOneWidget);
      expect(find.byIcon(Icons.pause_circle_outline), findsOneWidget);
    });

    testWidgets('affiche le mm:ss quand hors avertissement', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: PassageCountdownBadge(
            tempsRestant: Duration(minutes: 3, seconds: 5),
            avertissementLeadSec: 30,
            enPause: false,
          ),
        ),
      ));

      await tester.pump();

      expect(find.text('03:05'), findsOneWidget);
    });

    testWidgets(
      'état avertissement actif : ne hang JAMAIS malgré la pulsation en boucle',
          (tester) async {
        await tester.pumpWidget(const MaterialApp(
          home: Scaffold(
            body: PassageCountdownBadge(
              tempsRestant: Duration(seconds: 20),
              avertissementLeadSec: 30,
              enPause: false,
            ),
          ),
        ));

        // IMPORTANT : jamais pumpAndSettle() ici — l'animation de pulsation
        // (_Pulsing, ..repeat(reverse: true)) tourne indéfiniment tant que le
        // badge est monté. On avance le temps par pas fixes et bornés.
        await tester.pump();
        await tester.pump(const Duration(milliseconds: 350));
        await tester.pump(const Duration(milliseconds: 350));
        await tester.pump(const Duration(milliseconds: 350));

        expect(find.text('00:20'), findsOneWidget);
        expect(find.byIcon(Icons.notifications_active_outlined), findsOneWidget);
      },
    );

    testWidgets('affiche l\'état "dépassé" en rouge avec un +', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: PassageCountdownBadge(
            tempsRestant: Duration(seconds: -12),
            avertissementLeadSec: 30,
            enPause: false,
          ),
        ),
      ));

      await tester.pump();

      expect(find.textContaining('+00:12'), findsOneWidget);
      expect(find.byIcon(Icons.timer_off), findsOneWidget);
    });
  });

  group('PassageWarningBanner (widget)', () {
    testWidgets('invisible hors fenêtre de préavis', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: PassageWarningBanner(
            tempsRestant: Duration(minutes: 5),
            avertissementLeadSec: 60,
            enPause: false,
          ),
        ),
      ));
      await tester.pump();

      // #333 : Scoper la recherche au sous-arbre du banner : Scaffold crée lui-même un
      // Material interne, donc find.byType(Material) sans scope trouve toujours
      // quelque chose et ne peut jamais échouer utilement.
      final materialDuBanner = find.descendant(
        of: find.byType(PassageWarningBanner),
        matching: find.byType(Material),
      );
      expect(materialDuBanner, findsNothing);
      expect(find.textContaining('Fin de passage'), findsNothing);
    });

    testWidgets('invisible pendant une pause même dans la fenêtre de préavis',
            (tester) async {
          await tester.pumpWidget(const MaterialApp(
            home: Scaffold(
              body: PassageWarningBanner(
                tempsRestant: Duration(seconds: 10),
                avertissementLeadSec: 60,
                enPause: true,
              ),
            ),
          ));
          await tester.pump();

          expect(find.textContaining('Fin de passage'), findsNothing);
        });

    testWidgets('visible et lisible dans la fenêtre de préavis', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: PassageWarningBanner(
            tempsRestant: Duration(seconds: 45),
            avertissementLeadSec: 60,
            enPause: false,
          ),
        ),
      ));
      await tester.pump();

      expect(find.textContaining('Fin de passage dans 45s'), findsOneWidget);
    });
  });
}