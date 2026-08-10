// test/unit/sync_wiring_test.dart
// ================================================
// #307 — LE FIL MANQUANT.
//
// Le service de synchronisation était complet : un singleton, deux flux, un
// BLoC abonné, une bannière, des compteurs. Il manquait UNE ligne — personne
// n'appelait `SyncService.instance.init(repository)`. Résultat : `syncNow()`
// sortait à sa deuxième ligne et AUCUNE notation saisie hors ligne n'a jamais
// été remontée au serveur. L'écran, lui, affichait un compteur plausible.
//
// Aucun test unitaire classique n'attrape ça : chaque pièce fonctionne, c'est
// l'assemblage qui manque. On vérifie donc l'assemblage lui-même, à la source.
// C'est volontairement rustique — et c'est exactement la régression à empêcher.

import 'dart:io';

import 'package:flutter_test/flutter_test.dart';

void main() {
  test('#307 — main.dart câble bien SyncService (sinon le hors-ligne est mort)',
      () {
    final source = File('lib/main.dart').readAsStringSync();

    expect(
      source.contains('SyncService.instance.init('),
      isTrue,
      reason:
          'main.dart doit appeler SyncService.instance.init(gradingRepository). '
          'Sans cet appel, les notes saisies hors ligne restent sur le '
          'téléphone et ne partent JAMAIS au serveur (#307).',
    );
  });

  test('#307 — le repository de synchronisation est celui de l\'app', () {
    final source = File('lib/main.dart').readAsStringSync();

    // Le câblage doit venir APRÈS le choix mock/réel, sinon on synchroniserait
    // avec une maquette (ou avec null).
    final indexChoix   = source.indexOf('gradingRepository = ');
    final indexCablage = source.indexOf('SyncService.instance.init(');

    expect(indexChoix, greaterThan(-1));
    expect(indexCablage, greaterThan(indexChoix),
        reason: 'init() doit suivre la construction du repository');
  });
}
