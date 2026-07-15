// lib/features/grading/presentation/bloc/grading_bloc.dart
// ================================================
// BF6.1 — WebSocket : réception des mises à jour de scores en temps réel.
// BF6.2 — Offline  : la sauvegarde locale est transparente (gérée par le
//          repository). Le bloc notifie l'OfflineBloc après chaque saisie
//          pour maintenir le compteur de notations en attente à jour.
//
// #197 — Consommation live du WebSocket sur l'écran de notation :
//   1) le lot courant est désormais aussi suivi (onLotStatusUpdate), pas
//      seulement les scores d'une station ;
//   2) un score poussé par le serveur ne doit JAMAIS écraser l'affichage
//      d'une saisie locale pas encore synchronisée : tant qu'une sauvegarde
//      est en vol pour un étudiant (offline ou juste envoyée), les mises à
//      jour WebSocket le concernant sont ignorées (voir _pendingSaves /
//      _onWsScoreReceived) ;
//   3) une coupure réseau ne bloque jamais la saisie : WebSocketService gère
//      seul le backoff/la reconnexion et réabonne automatiquement les topics
//      actifs (voir websocket_service.dart), le bloc n'a rien à faire de plus
//      qu'écouter les streams.

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
  final int       stationId;
  final int       lotNumero;
  final int?      grilleId;
  final DateTime? debutCreneau;

  const GradingSessionStarted({
    required this.stationId,
    required this.lotNumero,
    this.grilleId,
    this.debutCreneau,
  });

  @override
  List<Object?> get props => [stationId, lotNumero, grilleId, debutCreneau];
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

class GradingLotValide extends GradingEvent {
  const GradingLotValide();
}

class GradingLotSuivantDemande extends GradingEvent {
  const GradingLotSuivantDemande();
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

/// #197 — Changement de statut du lot courant reçu depuis le serveur
/// (ex : verrouillage confirmé depuis un autre appareil de l'évaluateur).
class GradingWsLotStatusReceived extends GradingEvent {
  final int    lotId;
  final String statut;

  const GradingWsLotStatusReceived({
    required this.lotId,
    required this.statut,
  });

  @override
  List<Object?> get props => [lotId, statut];
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

  const GradingLoaded({
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
    Map<int, Map<int, Notation>>? notations,
    Set<int>?              etudiantsValides,
    Duration?              tempsRestant,
    bool?                  lotEnCoursDeValidation,
    String?                messageSucces,
    Lot?                   lot,
    bool?                  lotValide,
    Map<int, double>?      wsScores,
  }) =>
      GradingLoaded(
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
      );

  @override
  List<Object?> get props => [
    stationId, grilleId, stationNom, grille, lot,
    notations, etudiantsValides, tempsRestant,
    lotEnCoursDeValidation, messageSucces, lotValide, wsScores,
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

  Timer?                             _timer;
  StreamSubscription<ScoreUpdate>?    _wsScoreSub; // BF6.1
  StreamSubscription<LotStatusUpdate>? _wsLotSub;  // #197

  /// #197 — Compte, par étudiant, le nombre de sauvegardes envoyées mais pas
  /// encore confirmées (succès OU échec — l'important est "en vol"). Tant que
  /// ce compteur est > 0 pour un étudiant, un score poussé par le WebSocket
  /// pour ce même étudiant est ignoré : la saisie locale reste la source de
  /// vérité affichée jusqu'à ce que sa sauvegarde ait abouti. Évite qu'une
  /// notification WS "en retard" écrase visuellement une note qui vient
  /// d'être tapée par l'évaluateur.
  final Map<int, int> _pendingSaves = {};

  static const _durationStation = Duration(minutes: 15);

  GradingBloc({
    required GradingRepository repository,
    this.offlineBloc,
  })  : _repository = repository,
        super(GradingInitial()) {
    on<GradingSessionStarted>   (_onSessionStarted);
    on<GradingBinaryUpdated>    (_onBinaryUpdated);
    on<GradingNumericUpdated>   (_onNumericUpdated);
    on<GradingEtudiantValide>   (_onEtudiantValide);
    on<GradingLotValide>        (_onLotValide);
    on<GradingLotSuivantDemande>(_onLotSuivant);
    on<GradingEtudiantSubstitue>(_onSubstituer);
    on<GradingTimerTick>        (_onTimerTick);
    on<GradingWsScoreReceived>  (_onWsScoreReceived);   // BF6.1
    on<GradingWsLotStatusReceived>(_onWsLotStatusReceived); // #197
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
        _repository.getLot(event.stationId, event.lotNumero),
      ]);

      final grille   = results[0] as Grille;
      final lot      = results[1] as Lot;
      final grilleId = event.grilleId ?? grille.id;

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
        stationId:        event.stationId,
        grilleId:         grilleId,
        stationNom:       'Station ${event.stationId}',
        grille:           grille,
        lot:              lot,
        notations:        notations,
        etudiantsValides: etudiantsValides,
        tempsRestant:     tempsRestant,
        lotValide:        lot.valide,
      ));

      _startTimer(tempsRestant);

      // BF6.1 / #197 — Souscription WebSocket pour cette station ET ce lot.
      _subscribeToWebSocket(event.stationId, lot.id);
    } catch (e) {
      emit(GradingError('Impossible de charger la session : $e'));
    }
  }

  // ── BF6.1 / #197 — Souscriptions WebSocket ───────────────────────────────
  void _subscribeToWebSocket(int stationId, int lotId) {
    _wsScoreSub?.cancel();
    WebSocketService.instance.subscribeToStation(stationId);

    _wsScoreSub = WebSocketService.instance.onScoreUpdate.listen((update) {
      if (update.stationId == stationId) {
        add(GradingWsScoreReceived(
          etudiantId: update.etudiantId,
          stationId:  update.stationId,
          score:      update.score,
          verrouille: update.verrouille,
        ));
      }
    });

    // #197 — Le statut du lot courant (ex : verrouillé depuis un autre
    // appareil du même évaluateur) doit se refléter sans refresh manuel.
    _wsLotSub?.cancel();
    WebSocketService.instance.subscribeToLot(lotId);
    _wsLotSub = WebSocketService.instance.onLotStatusUpdate.listen((update) {
      add(GradingWsLotStatusReceived(lotId: update.lotId, statut: update.statut));
    });
  }

  // ── BF6.1 — Réception score WebSocket ────────────────────────────────────
  void _onWsScoreReceived(
      GradingWsScoreReceived event,
      Emitter<GradingState> emit,
      ) {
    final current = state;
    if (current is! GradingLoaded) return;

    // #197 — Garde-fou anti-écrasement : une sauvegarde locale est en vol
    // pour cet étudiant (saisie pas encore confirmée / hors-ligne) → on
    // ignore ce score serveur, potentiellement obsolète par rapport à ce que
    // l'évaluateur vient de taper. Le prochain événement WS, une fois la
    // sauvegarde locale confirmée, sera à nouveau pris en compte normalement.
    if ((_pendingSaves[event.etudiantId] ?? 0) > 0) {
      return;
    }

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

  // ── #197 — Réception statut de lot WebSocket ─────────────────────────────
  void _onWsLotStatusReceived(
      GradingWsLotStatusReceived event,
      Emitter<GradingState> emit,
      ) {
    final current = state;
    if (current is! GradingLoaded) return;
    if (event.lotId != current.lot.id) return; // pas notre lot courant

    if (event.statut == 'TERMINE' && !current.lotValide) {
      emit(current.copyWith(
        lotValide:     true,
        messageSucces: 'Lot ${current.lot.numero} validé',
      ));
    }
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

  /// BF6.2 / #197 — Sauvegarde la notation (online ou locale), notifie
  /// l'OfflineBloc, et tient à jour le compteur de sauvegardes "en vol" par
  /// étudiant pour protéger l'affichage contre un écrasement WebSocket
  /// (voir _pendingSaves / _onWsScoreReceived).
  void _saveAndRefreshOffline(Notation notation) {
    _pendingSaves[notation.etudiantId] =
        (_pendingSaves[notation.etudiantId] ?? 0) + 1;

    _repository.saveNotation(notation).whenComplete(() {
      final remaining = (_pendingSaves[notation.etudiantId] ?? 1) - 1;
      if (remaining <= 0) {
        _pendingSaves.remove(notation.etudiantId);
      } else {
        _pendingSaves[notation.etudiantId] = remaining;
      }
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

  // ── Validation du lot ─────────────────────────────────────────────────────
  Future<void> _onLotValide(
      GradingLotValide event,
      Emitter<GradingState> emit,
      ) async {
    final current = state;
    if (current is! GradingLoaded) return;

    emit(current.copyWith(lotEnCoursDeValidation: true));
    try {
      await _repository.validerLot(current.lot.id);
      emit(current.copyWith(
        lotEnCoursDeValidation: false,
        lotValide:              true,
        messageSucces:          'Lot ${current.lot.numero} validé !',
      ));
    } catch (_) {
      emit(current.copyWith(lotEnCoursDeValidation: false));
    }
  }

  // ── Lot suivant ───────────────────────────────────────────────────────────
  Future<void> _onLotSuivant(
      GradingLotSuivantDemande event,
      Emitter<GradingState> emit,
      ) async {
    final current = state;
    if (current is! GradingLoaded) return;

    final prochainLot = current.lot.numero + 1;
    if (prochainLot > current.lot.total) return;

    emit(GradingLoading());
    try {
      final lot = await _repository.getLot(current.stationId, prochainLot);
      emit(GradingLoaded(
        stationId:        current.stationId,
        grilleId:         current.grilleId,
        stationNom:       current.stationNom,
        grille:           current.grille,
        lot:              lot,
        notations:        {},
        etudiantsValides: {},
        tempsRestant:     _durationStation,
        lotValide:        lot.valide,
      ));
      _startTimer();
      // #197 — nouveau lot → suivre son statut en temps réel lui aussi.
      _subscribeToWebSocket(current.stationId, lot.id);
    } catch (e) {
      emit(GradingError('Impossible de charger le lot suivant : $e'));
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

  void _startTimer([Duration? initialDuration]) {
    _timer?.cancel();
    var restant = initialDuration ?? _durationStation;
    _timer = Timer.periodic(const Duration(seconds: 1), (_) {
      restant -= const Duration(seconds: 1);
      add(GradingTimerTick(restant));
    });
  }

  Duration _computeTempsRestant(DateTime? debutCreneau) {
    if (debutCreneau == null) return _durationStation;
    final elapsed = DateTime.now().difference(debutCreneau);
    return _durationStation - elapsed;
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
    _wsScoreSub?.cancel();
    _wsLotSub?.cancel();
    // Désabonnement WebSocket propre
    final current = state;
    if (current is GradingLoaded) {
      WebSocketService.instance.unsubscribeFromStation(current.stationId);
      WebSocketService.instance.unsubscribeFromLot(current.lot.id);
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