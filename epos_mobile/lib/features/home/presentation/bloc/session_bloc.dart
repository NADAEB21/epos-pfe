// lib/features/home/presentation/bloc/session_bloc.dart
//
// #197 — Le dashboard évaluateur (liste des sessions / planning du jour)
// s'abonne désormais aux mises à jour de statut de lot poussées par le
// WebSocket (voir scoring-service EvaluateurDashboardService.broadcastLotStatus)
// et se rafraîchit automatiquement dès qu'un lot pertinent change d'état —
// plus besoin de pull-to-refresh pour voir un lot validé ailleurs.
//
// Un léger debounce évite de déclencher une rafale d'appels réseau si
// plusieurs notifications arrivent en cluster (ex: verrouillage de plusieurs
// étudiants d'un coup en fin de lot).

import 'dart:async';

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

import '../../../../core/offline/websocket_service.dart';
import '../../domain/entities/session.dart';
import '../../domain/repositories/session_repository.dart';

// ========================
// EVENTS
// ========================
abstract class SessionEvent extends Equatable {
  const SessionEvent();
  @override
  List<Object?> get props => [];
}

class SessionLoadRequested extends SessionEvent {
  const SessionLoadRequested();
}

class SessionRefreshRequested extends SessionEvent {
  const SessionRefreshRequested();
}

// ========================
// STATES
// ========================
abstract class SessionState extends Equatable {
  const SessionState();
  @override
  List<Object?> get props => [];
}

class SessionInitial extends SessionState {}
class SessionLoading  extends SessionState {}

class SessionError extends SessionState {
  final String message;
  const SessionError(this.message);
  @override
  List<Object?> get props => [message];
}

class SessionLoaded extends SessionState {
  final List<Session>     sessions;
  final EvaluateurStats   stats;
  final List<PlanningCell> planning;
  final Duration clockOffset; 

  const SessionLoaded({
    required this.sessions,
    required this.stats,
    required this.planning,
    this.clockOffset = Duration.zero,
  });

  /// Sessions en cours uniquement
  List<Session> get sessionsEnCours =>
      sessions.where((s) => s.statut == SessionStatus.enCours).toList();

  /// Sessions à venir uniquement
  List<Session> get sessionsAVenir =>
      sessions.where((s) => s.statut == SessionStatus.aVenir).toList();

  /// Heures uniques du planning (pour les lignes du tableau)
  List<String> get heures =>
      planning.map((c) => c.heure).toSet().toList()..sort();

  /// Numéros de lots uniques (pour les colonnes du tableau)
  List<int> get lots =>
      planning.map((c) => c.lotNumero).toSet().toList()..sort();

  /// Récupère une cellule précise du planning
  CellStatus cellStatus(String heure, int lot) {
    final cell = planning.where(
          (c) => c.heure == heure && c.lotNumero == lot,
    );
    return cell.isEmpty ? CellStatus.aucun : cell.first.statut;
  }

  @override
  List<Object?> get props => [sessions, stats, planning, clockOffset];
}

// ========================
// BLOC
// ========================
class SessionBloc extends Bloc<SessionEvent, SessionState> {
  final SessionRepository _repository;

  // #197 — Écoute des changements de statut de lot pour rafraîchir le
  // dashboard en live. Débounce pour éviter les rafales d'appels réseau.
  StreamSubscription<LotStatusUpdate>? _lotStatusSub;
  Timer? _refreshDebounce;
  static const _debounceDelay = Duration(milliseconds: 600);

  SessionBloc({required SessionRepository repository})
      : _repository = repository,
        super(SessionInitial()) {
    on<SessionLoadRequested>   (_onLoad);
    on<SessionRefreshRequested>(_onRefresh);

    _lotStatusSub = WebSocketService.instance.onLotStatusUpdate
        .listen(_onLotStatusUpdate);
  }

  /// #197 — Ne déclenche un rafraîchissement que si le lot concerné fait
  /// partie des sessions actuellement affichées (évite des refresh inutiles
  /// pour un lot appartenant à un autre évaluateur/examen).
  void _onLotStatusUpdate(LotStatusUpdate update) {
    final current = state;
    if (current is! SessionLoaded) return;

    final estPertinent = current.sessions.any((s) => s.lotId == update.lotId);
    if (!estPertinent) return;

    _refreshDebounce?.cancel();
    _refreshDebounce = Timer(_debounceDelay, () {
      if (!isClosed) add(const SessionRefreshRequested());
    });
  }

  Future<void> _onLoad(
      SessionLoadRequested event,
      Emitter<SessionState> emit,
      ) async {
    emit(SessionLoading());
    await _fetchData(emit);
  }

  Future<void> _onRefresh(
      SessionRefreshRequested event,
      Emitter<SessionState> emit,
      ) async {
    // Refresh sans remettre le spinner si déjà chargé
    await _fetchData(emit);
  }

  Future<void> _fetchData(Emitter<SessionState> emit) async {
    try {
      final results = await Future.wait([
        _repository.getSessions(),
        _repository.getStats(),
        _repository.getPlanningDuJour(),
        _repository.getClockOffset(),
      ]);

      final sessions = results[0] as List<Session>;

      emit(SessionLoaded(
        sessions: sessions,
        stats:    results[1] as EvaluateurStats,
        planning: results[2] as List<PlanningCell>,
        clockOffset: results[3] as Duration, 
      ));

      WebSocketService.instance.syncLotSubscriptions(
        sessions.where((s) => s.lotId != null).map((s) => s.lotId!),
      );

      // #197 — Aligne les abonnements WebSocket "lot" sur la liste de
      // sessions fraîchement chargée (ajoute les nouveaux, retire les
      // obsolètes) pour que le dashboard reste live sans fuite d'abonnement.
      WebSocketService.instance
          .syncLotSubscriptions(sessions.map((s) => s.id));
    } catch (e) {
      emit(SessionError('Impossible de charger les sessions : $e'));
    }
  }

  @override
  Future<void> close() {
    _lotStatusSub?.cancel();
    _refreshDebounce?.cancel();
    return super.close();
  }
}