// lib/features/grading/presentation/bloc/grading_bloc.dart
// ================================================
// BF6.1 — WebSocket : réception des mises à jour de scores en temps réel.
// BF6.2 — Offline  : la sauvegarde locale est transparente (gérée par le
//          repository). Le bloc notifie l'OfflineBloc après chaque saisie
//          pour maintenir le compteur de notations en attente à jour.
//
// #196 (ADR-0012) — Avertissement inter-créneau + compte à rebours pause-aware :
//   - `avertissementLeadSec` (issu de Session, lui-même issu du backend) est
//     transporté dans l'état pour piloter PassageCountdownBadge /
//     PassageWarningBanner (voir grading_screen.dart) ;
//   - `enPause` gèle le compte à rebours (ADR-0009) : le Timer local ne
//     décrémente plus tant que l'examen est en pause, et reprend exactement
//     où il en était à la reprise — jamais de saut de temps ni de faux
//     avertissement déclenché pendant une pause ;
//   - `enPause` est mis à jour en direct via GradingPauseStateUpdated, que
//     l'écran (grading_screen.dart) déclenche en observant le SessionBloc
//     déjà injecté dans l'arbre de widgets (voir home_screen.dart) — pas de
//     nouvel appel réseau, on réutilise les données déjà pollées/poussées
//     pour le dashboard.

import 'dart:async';

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

import '../../domain/entities/grille.dart';
import '../../domain/entities/item_evaluation.dart';
import '../../domain/entities/lot.dart';
import '../../domain/entities/notation.dart';
import '../../domain/repositories/grading_repository.dart';
import '../../../../core/offline/offline_bloc.dart';
import '../../../../core/offline/websocket_service.dart';
import '../../../../core/utils/score_utils.dart';

// ════════════════════════════════════════════════
// EVENTS
// ════════════════════════════════════════════════
abstract class GradingEvent extends Equatable {
  const GradingEvent();
  @override
  List<Object?> get props => [];
}

class GradingSessionStarted extends GradingEvent {
  final int       rotationId;
  final int       stationId;
  final int       lotNumero;
  final int?      grilleId;
  final DateTime? debutCreneau;
  final String stationNom;
  final int  dureeMinutes;

  /// #196 — Délai de préavis (s) avant fin de passage, et état de pause
  /// initial, transmis depuis la Session choisie sur le dashboard.
  final int  avertissementLeadSec;
  final bool enPause;
  final Duration clockOffset; 

  const GradingSessionStarted({
    required this.rotationId,
    required this.stationId,
    required this.lotNumero,
    required this.stationNom,
    this.grilleId,
    this.debutCreneau,
    this.avertissementLeadSec = 0,
    this.enPause = false,
    this.dureeMinutes = 15,
    this.clockOffset = Duration.zero,
  });

  @override
  List<Object?> get props =>
      [rotationId, stationId, lotNumero, grilleId, debutCreneau, stationNom, avertissementLeadSec, enPause, dureeMinutes, clockOffset];
}

class GradingBinaryUpdated extends GradingEvent {
  final int   etudiantId;
  final int   itemId;
  final bool? fait; // null = effacé, true = Fait, false = Non fait

  const GradingBinaryUpdated({
    required this.etudiantId,
    required this.itemId,
    required this.fait,
  });

  @override
  List<Object?> get props => [etudiantId, itemId, fait];
}

class GradingNumericUpdated extends GradingEvent {
  final int    etudiantId;
  final int    itemId;
  final double valeur;

  const GradingNumericUpdated({
    required this.etudiantId,
    required this.itemId,
    required this.valeur,
  });

  @override
  List<Object?> get props => [etudiantId, itemId, valeur];
}

class GradingEtudiantValide extends GradingEvent {
  final int     etudiantId;
  final bool    absent;
  final String? commentaire;

  const GradingEtudiantValide(
      this.etudiantId, {
        this.absent      = false,
        this.commentaire,
      });

  @override
  List<Object?> get props => [etudiantId, absent, commentaire];
}

class GradingGroupeValide extends GradingEvent {
  const GradingGroupeValide();
}

class GradingGroupeSuivantDemande extends GradingEvent {
  const GradingGroupeSuivantDemande();
}

class GradingEtudiantSubstitue extends GradingEvent {
  final int etudiantAbsentId;
  final int etudiantRemplacantId;

  const GradingEtudiantSubstitue({
    required this.etudiantAbsentId,
    required this.etudiantRemplacantId,
  });

  @override
  List<Object?> get props => [etudiantAbsentId, etudiantRemplacantId];
}

class GradingTimerTick extends GradingEvent {
  final Duration restant;
  const GradingTimerTick(this.restant);
  @override
  List<Object?> get props => [restant];
}

/// BF6.1 — Mise à jour de score reçue depuis le serveur via WebSocket.
/// Appliquée en lecture seule : ne modifie pas les notations locales.
class GradingWsScoreReceived extends GradingEvent {
  final int    etudiantId;
  final int    stationId;
  final double score;
  final bool   verrouille;

  const GradingWsScoreReceived({
    required this.etudiantId,
    required this.stationId,
    required this.score,
    required this.verrouille,
  });

  @override
  List<Object?> get props => [etudiantId, stationId, score, verrouille];
}

/// #196 (ADR-0009) — L'état de pause de l'examen a changé (rapporté par
/// l'écran de notation, qui observe le SessionBloc déjà chargé). Fige ou
/// relâche le compte à rebours en conséquence.
class GradingPauseStateUpdated extends GradingEvent {
  final bool enPause;
  const GradingPauseStateUpdated(this.enPause);
  @override
  List<Object?> get props => [enPause];
}

// ════════════════════════════════════════════════
// STATES
// ════════════════════════════════════════════════
abstract class GradingState extends Equatable {
  const GradingState();
  @override
  List<Object?> get props => [];
}

class GradingInitial extends GradingState {}
class GradingLoading extends GradingState {}

class GradingError extends GradingState {
  final String message;
  const GradingError(this.message);
  @override
  List<Object?> get props => [message];
}

class GradingLoaded extends GradingState {
  final int                          rotationId;
  final int                          stationId;
  final int                          grilleId;
  final String                       stationNom;
  final Grille                       grille;
  final Lot                          lot;
  final Map<int, Map<int, Notation>> notations;
  final Set<int>                     etudiantsValides;
  final Duration?                    tempsRestant;
  final bool                         lotEnCoursDeValidation;
  final String?                      messageSucces;
  final bool                         lotValide;

  /// BF6.1 — Scores reçus via WebSocket (etudiantId → score serveur).
  /// Ces valeurs sont affichées en priorité sur le calcul local
  /// car le serveur applique les pondérations de la grille.
  final Map<int, double>             wsScores;

  /// #196 (ADR-0012) — Délai de préavis (s) avant la fin du passage courant.
  final int  avertissementLeadSec;

  /// #196 (ADR-0009) — L'examen est actuellement en pause : le compte à
  /// rebours affiché par l'UI doit être figé (voir _startTimer).
  final bool enPause;

  const GradingLoaded({
    required this.rotationId,
    required this.stationId,
    required this.grilleId,
    required this.stationNom,
    required this.grille,
    required this.lot,
    required this.notations,
    required this.etudiantsValides,
    this.tempsRestant,
    this.lotEnCoursDeValidation = false,
    this.messageSucces,
    this.lotValide    = false,
    this.wsScores     = const {},
    this.avertissementLeadSec = 0,
    this.enPause = false,
  });

  // ── Calculs de score ──────────────────────────
  /// Score affiché : priorité au score serveur (WebSocket) si disponible,
  /// sinon calcul local temps réel. Garantit la cohérence même hors-ligne.
  double scoreEtudiant(int etudiantId) {
    if (wsScores.containsKey(etudiantId)) {
      return wsScores[etudiantId]!;
    }
    return ScoreUtils.calculerScore(
      items:     grille.items,
      notations: notations[etudiantId] ?? {},
    );
  }

  double progressionEtudiant(int etudiantId) => ScoreUtils.progression(
    items:     grille.items,
    notations: notations[etudiantId] ?? {},
  );

  bool etudiantComplet(int etudiantId) =>
      progressionEtudiant(etudiantId) == 1.0;

  bool get tousLesEtudiantsValides =>
      lot.etudiants.every((e) => etudiantsValides.contains(e.id));

  GradingLoaded copyWith({
    int?                   rotationId,
    Map<int, Map<int, Notation>>? notations,
    Set<int>?              etudiantsValides,
    Duration?              tempsRestant,
    bool?                  lotEnCoursDeValidation,
    String?                messageSucces,
    Lot?                   lot,
    bool?                  lotValide,
    Map<int, double>?      wsScores,
    int?                   avertissementLeadSec,
    bool?                  enPause,
  }) =>
      GradingLoaded(
        rotationId:             rotationId       ?? this.rotationId,
        stationId:              stationId,
        grilleId:               grilleId,
        stationNom:             stationNom,
        grille:                 grille,
        lot:                    lot              ?? this.lot,
        notations:              notations        ?? this.notations,
        etudiantsValides:       etudiantsValides ?? this.etudiantsValides,
        tempsRestant:           tempsRestant     ?? this.tempsRestant,
        lotEnCoursDeValidation: lotEnCoursDeValidation ?? this.lotEnCoursDeValidation,
        messageSucces:          messageSucces,
        lotValide:              lotValide ?? this.lotValide,
        wsScores:               wsScores  ?? this.wsScores,
        avertissementLeadSec:   avertissementLeadSec ?? this.avertissementLeadSec,
        enPause:                enPause ?? this.enPause,
      );

  @override
  List<Object?> get props => [
    rotationId, stationId, grilleId, stationNom, grille, lot,
    notations, etudiantsValides, tempsRestant,
    lotEnCoursDeValidation, messageSucces, lotValide, wsScores,
    avertissementLeadSec, enPause,
  ];
}

// ════════════════════════════════════════════════
// BLOC
// ════════════════════════════════════════════════
class GradingBloc extends Bloc<GradingEvent, GradingState> {
  final GradingRepository _repository;

  /// BF6 — Référence optionnelle à l'OfflineBloc pour rafraîchir le badge.
  /// Injectée depuis GradingScreen via le constructeur.
  final OfflineBloc? offlineBloc;

  Timer?                       _timer;
  StreamSubscription<ScoreUpdate>? _wsSub; // BF6.1

  Duration _dureeStation = const Duration(minutes: 15);
  Duration _clockOffset  = Duration.zero; 

  GradingBloc({
    required GradingRepository repository,
    this.offlineBloc,
  })  : _repository = repository,
        super(GradingInitial()) {
    on<GradingSessionStarted>   (_onSessionStarted);
    on<GradingBinaryUpdated>    (_onBinaryUpdated);
    on<GradingNumericUpdated>   (_onNumericUpdated);
    on<GradingEtudiantValide>   (_onEtudiantValide);
    on<GradingGroupeValide>        (_onGroupeValide);
    on<GradingGroupeSuivantDemande>(_onGroupeSuivant);
    on<GradingEtudiantSubstitue>(_onSubstituer);
    on<GradingTimerTick>        (_onTimerTick);
    on<GradingWsScoreReceived>  (_onWsScoreReceived); // BF6.1
    on<GradingPauseStateUpdated>(_onPauseStateUpdated); // #196
  }

  // ── Chargement initial ────────────────────────────────────────────────────
  Future<void> _onSessionStarted(
      GradingSessionStarted event,
      Emitter<GradingState> emit,
      ) async {
    emit(GradingLoading());
    try {
      final results = await Future.wait([
        _repository.getGrille(event.stationId),
        _repository.getGroupe(event.rotationId),
      ]);

      final grille   = results[0] as Grille;
      final lot      = results[1] as Lot;
      final grilleId = event.grilleId ?? grille.id;

      _dureeStation = Duration(minutes: event.dureeMinutes > 0 ? event.dureeMinutes : 15);
      _clockOffset  = event.clockOffset; 

      // ── Restaurer la progression et le verrouillage depuis le serveur ──
      final Map<int, Map<int, Notation>> notations        = {};
      final Set<int>                     etudiantsValides = {};

      for (final etudiant in lot.etudiants) {
        if (etudiant.absent || etudiant.verrouille) {
          etudiantsValides.add(etudiant.id);
        }
        if (etudiant.notationExistante.isNotEmpty) {
          notations[etudiant.id] = {
            for (final e in etudiant.notationExistante.entries)
              e.key: Notation(
                etudiantId: etudiant.id,
                itemId:     e.key,
                valeur:     e.value,
                stationId:  event.stationId,
                grilleId:   grilleId,
              ),
          };
        }
      }

      final tempsRestant = _computeTempsRestant(event.debutCreneau);

      emit(GradingLoaded(
        rotationId:       event.rotationId,
        stationId:        event.stationId,
        grilleId:         grilleId,
        stationNom:       event.stationNom,
        grille:           grille,
        lot:              lot,
        notations:        notations,
        etudiantsValides: etudiantsValides,
        tempsRestant:     tempsRestant,
        lotValide:        lot.valide,
        avertissementLeadSec: event.avertissementLeadSec,
        enPause:              event.enPause,
      ));

      _startTimer(tempsRestant);

      // BF6.1 — Souscription WebSocket pour cette station
      _subscribeToWebSocket(event.stationId);
    } catch (e) {
      emit(GradingError('Impossible de charger la session : $e'));
    }
  }

  // ── BF6.1 — Souscription WebSocket ───────────────────────────────────────
  void _subscribeToWebSocket(int stationId) {
    _wsSub?.cancel();
    WebSocketService.instance.subscribeToStation(stationId);

    _wsSub = WebSocketService.instance.onScoreUpdate.listen((update) {
      if (update.stationId == stationId) {
        add(GradingWsScoreReceived(
          etudiantId: update.etudiantId,
          stationId:  update.stationId,
          score:      update.score,
          verrouille: update.verrouille,
        ));
      }
    });
  }

  // ── BF6.1 — Réception score WebSocket ────────────────────────────────────
  void _onWsScoreReceived(
      GradingWsScoreReceived event,
      Emitter<GradingState> emit,
      ) {
    final current = state;
    if (current is! GradingLoaded) return;

    final updatedWsScores = Map<int, double>.from(current.wsScores)
      ..[event.etudiantId] = event.score;

    // Si le serveur indique que l'étudiant est verrouillé, on le marque
    final updatedValides = Set<int>.from(current.etudiantsValides);
    if (event.verrouille) updatedValides.add(event.etudiantId);

    emit(current.copyWith(
      wsScores:         updatedWsScores,
      etudiantsValides: updatedValides,
    ));
  }

  // ── #196 (ADR-0009) — Pause / reprise ────────────────────────────────────
  /// Met simplement à jour le flag `enPause` de l'état. Le Timer (voir
  /// `_startTimer`) lit `state.enPause` à chaque tick et gèle la décrémentation
  /// en conséquence — aucune resynchronisation de durée nécessaire ici : à la
  /// reprise, le compte à rebours continue exactement là où il s'était arrêté.
  void _onPauseStateUpdated(
      GradingPauseStateUpdated event,
      Emitter<GradingState> emit,
      ) {
    final current = state;
    if (current is! GradingLoaded) return;
    if (current.enPause == event.enPause) return;
    emit(current.copyWith(enPause: event.enPause));
  }

  // ── Mise à jour critère binaire ───────────────────────────────────────────
  void _onBinaryUpdated(
      GradingBinaryUpdated event,
      Emitter<GradingState> emit,
      ) {
    final current = state;
    if (current is! GradingLoaded) return;
    if (current.etudiantsValides.contains(event.etudiantId)) return;
    if (current.lotValide) return;

    Map<int, Map<int, Notation>> updatedNotations;

    if (event.fait == null) {
      // Case décochée → supprime la notation locale
      final etudiantNotations =
      Map<int, Notation>.from(current.notations[event.etudiantId] ?? {});
      etudiantNotations.remove(event.itemId);
      updatedNotations = {
        ...current.notations,
        event.etudiantId: etudiantNotations,
      };
    } else {
      updatedNotations = _updateNotation(
        current.notations,
        event.etudiantId,
        event.itemId,
        event.fait! ? 1.0 : 0.0,
      );

      // BF6.2 — saveNotation gère lui-même online/offline (repository)
      _saveAndRefreshOffline(Notation(
        etudiantId: event.etudiantId,
        itemId:     event.itemId,
        valeur:     event.fait! ? 1.0 : 0.0,
        stationId:  current.stationId,
        grilleId:   current.grilleId,
      ));
    }

    emit(current.copyWith(notations: updatedNotations));
  }

  // ── Mise à jour critère numérique ─────────────────────────────────────────
  void _onNumericUpdated(
      GradingNumericUpdated event,
      Emitter<GradingState> emit,
      ) {
    final current = state;
    if (current is! GradingLoaded) return;
    if (current.etudiantsValides.contains(event.etudiantId)) return;
    if (current.lotValide) return;

    final item        = _trouverItemDansArbre(current.grille.items, event.itemId);
    final valeurClamp = event.valeur.clamp(0.0, item.valeurMax);

    final updated = _updateNotation(
      current.notations,
      event.etudiantId,
      event.itemId,
      valeurClamp,
    );
    emit(current.copyWith(notations: updated));

    // BF6.2 — saveNotation gère lui-même online/offline (repository)
    _saveAndRefreshOffline(Notation(
      etudiantId: event.etudiantId,
      itemId:     event.itemId,
      valeur:     valeurClamp,
      stationId:  current.stationId,
      grilleId:   current.grilleId,
    ));
  }

  /// BF6.2 — Sauvegarde la notation (online ou locale) puis notifie l'OfflineBloc.
  void _saveAndRefreshOffline(Notation notation) {
    _repository.saveNotation(notation).then((_) {
      // Rafraîchit le compteur du badge dans l'OfflineBloc
      offlineBloc?.refreshPendingCount();
    });
  }

  // ── Validation d'un étudiant ──────────────────────────────────────────────
  Future<void> _onEtudiantValide(
      GradingEtudiantValide event,
      Emitter<GradingState> emit,
      ) async {
    final current = state;
    if (current is! GradingLoaded) return;

    final updatedNotations = event.absent
        ? (Map<int, Map<int, Notation>>.from(current.notations)
      ..remove(event.etudiantId))
        : current.notations;

    emit(current.copyWith(
      etudiantsValides: {...current.etudiantsValides, event.etudiantId},
      notations:        updatedNotations,
    ));

    await _repository.validerEtudiant(
      event.etudiantId,
      current.stationId,
      grilleId:    current.grilleId,
      absent:      event.absent,
      commentaire: event.commentaire,
    );
  }

  // ── Validation du groupe ─────────────────────────────────────────────────────
  Future<void> _onGroupeValide(GradingGroupeValide event, Emitter<GradingState> emit) async {
    final current = state;
    if (current is! GradingLoaded) return;
    emit(current.copyWith(lotEnCoursDeValidation: true));
    try {
      await _repository.validerGroupe(current.rotationId);   // ← FIX
      emit(current.copyWith(
        lotEnCoursDeValidation: false, lotValide: true, messageSucces: 'Groupe validé !',
      ));
    } catch (_) {
      emit(current.copyWith(lotEnCoursDeValidation: false));
    }
  }

  // ── groupe suivant ───────────────────────────────────────────────────────────
  Future<void> _onGroupeSuivant(
      GradingGroupeSuivantDemande event,
      Emitter<GradingState> emit,
      ) async {
    final current = state;
    if (current is! GradingLoaded) return;
    emit(GradingLoading());
    try {
      final prochain = await _repository.getGroupeSuivant(current.rotationId);
      emit(GradingLoaded(
        rotationId: prochain.id, stationId: current.stationId, grilleId: current.grilleId,
        stationNom: current.stationNom, grille: current.grille, lot: prochain,
        notations: {}, etudiantsValides: {}, tempsRestant: _dureeStation,
        lotValide: prochain.valide,
        avertissementLeadSec: current.avertissementLeadSec, enPause: current.enPause,
      ));
      _startTimer();
    } catch (e) {
    emit(GradingError('Aucun groupe suivant disponible : $e'));
    }
  }

  // ── Substitution d'un étudiant ────────────────────────────────────────────
  Future<void> _onSubstituer(
      GradingEtudiantSubstitue event,
      Emitter<GradingState> emit,
      ) async {
    final current = state;
    if (current is! GradingLoaded) return;

    try {
      final remplacant = await _repository.substituerEtudiant(
        lotId:                current.lot.id,
        etudiantAbsentId:     event.etudiantAbsentId,
        etudiantRemplacantId: event.etudiantRemplacantId,
      );

      final etudiants = current.lot.etudiants
          .map((e) => e.id == event.etudiantAbsentId ? remplacant : e)
          .toList();

      emit(current.copyWith(
        lot: Lot(
          id:        current.lot.id,
          numero:    current.lot.numero,
          total:     current.lot.total,
          etudiants: etudiants,
          valide:    current.lot.valide,
        ),
      ));
    } catch (e) {
      emit(GradingError('Substitution impossible : $e'));
    }
  }

  // ── Timer ─────────────────────────────────────────────────────────────────
  void _onTimerTick(GradingTimerTick event, Emitter<GradingState> emit) {
    final current = state;
    if (current is! GradingLoaded) return;
    emit(current.copyWith(tempsRestant: event.restant));
  }

  /// #196 (ADR-0009) — Le compte à rebours se fige tant que `state.enPause`
  /// est vrai : la variable locale `restant` n'est simplement pas décrémentée
  /// à ce tick, donc à la reprise elle repart exactement d'où elle en était
  /// (pas de rattrapage, pas de saut de temps).
  void _startTimer([Duration? initialDuration]) {
    _timer?.cancel();
    var restant = initialDuration ?? _dureeStation;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      final current = state;
      final enPause = current is GradingLoaded && current.enPause;
      if (!enPause) {
        restant -= const Duration(seconds: 1);
      }
      add(GradingTimerTick(restant));
    });
  }

  Duration _computeTempsRestant(DateTime? debutCreneau) {
    if (debutCreneau == null) return _dureeStation;
    // ADR-0012 : "maintenant" corrigé du décalage horloge serveur/appareil —
    // sinon une horloge/fuseau d'appareil qui diffère du serveur recalcule un
    // temps écoulé faux à CHAQUE réouverture, donnant l'impression que le
    // minuteur "se réinitialise".
    // #239 — un `;` parasite terminait ce `return` (« return _dureeStation; - elapsed; ») :
    // `- elapsed` était du code mort, le compte à rebours renvoyait donc TOUJOURS la durée
    // pleine et ignorait `debutCreneau`. C'est exactement le symptôme « le minuteur se
    // réinitialise » que #232/#234 prétendaient corriger, et cela rendait `clockOffset`
    // inerte de bout en bout (son unique consommateur était `effectiveNow`, inutilisé).
    //
    // Un résultat NÉGATIF est voulu : PassageCountdownBadge le rend en « dépassement »
    // (`tempsRestant ≤ 0`, affiché en `.abs()`). Ne pas borner à zéro — ce serait un
    // plafond, et ADR-0014 n'autorise l'horloge qu'à poser un plancher.
    final effectiveNow = DateTime.now().add(_clockOffset);
    final elapsed = effectiveNow.difference(debutCreneau);
    return _dureeStation - elapsed;
  }

  // ── Utilitaire ────────────────────────────────────────────────────────────
  Map<int, Map<int, Notation>> _updateNotation(
      Map<int, Map<int, Notation>> current,
      int etudiantId,
      int itemId,
      double valeur,
      ) {
    final etudiantNotations =
    Map<int, Notation>.from(current[etudiantId] ?? {});
    etudiantNotations[itemId] = Notation(
      etudiantId: etudiantId,
      itemId:     itemId,
      valeur:     valeur,
    );
    return {...current, etudiantId: etudiantNotations};
  }

  @override
  Future<void> close() {
    _timer?.cancel();
    _wsSub?.cancel();
    // Désabonnement WebSocket propre
    final current = state;
    if (current is GradingLoaded) {
      WebSocketService.instance.unsubscribeFromStation(current.stationId);
    }
    return super.close();
  }

  // Dans GradingBloc — à ajouter avec les autres méthodes utilitaires privées

  /// #160 — Retrouve un item par son id, qu'il soit de premier niveau OU un
  /// sous-critère niché. grille.items ne contient QUE le premier niveau (voir
  /// ScoreUtils.feuilles()) : une simple recherche à plat rate systématiquement
  /// les sous-critères et lève une StateError silencieusement avalée par le
  /// bloc (la saisie semble fonctionner dans le champ mais n'est jamais
  /// persistée — c'est exactement le bug "le sous-critère numérique reste à 0").
  ItemEvaluation _trouverItemDansArbre(List<ItemEvaluation> items, int itemId) {
    for (final item in items) {
      if (item.id == itemId) return item;
      if (item.hasSousCriteres) {
        for (final enfant in item.sousCriteres) {
          if (enfant.id == itemId) return enfant;
        }
      }
    }
    throw StateError('Item introuvable : $itemId (ni en premier niveau, ni en sous-critère)');
  }
}
