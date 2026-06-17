// lib/features/home/presentation/bloc/session_bloc.dart

import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:equatable/equatable.dart';

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

  const SessionLoaded({
    required this.sessions,
    required this.stats,
    required this.planning,
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
  List<Object?> get props => [sessions, stats, planning];
}

// ========================
// BLOC
// ========================
class SessionBloc extends Bloc<SessionEvent, SessionState> {
  final SessionRepository _repository;

  SessionBloc({required SessionRepository repository})
      : _repository = repository,
        super(SessionInitial()) {
    on<SessionLoadRequested>   (_onLoad);
    on<SessionRefreshRequested>(_onRefresh);
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
      ]);

      emit(SessionLoaded(
        sessions: results[0] as List<Session>,
        stats:    results[1] as EvaluateurStats,
        planning: results[2] as List<PlanningCell>,
      ));
    } catch (e) {
      emit(SessionError('Impossible de charger les sessions : $e'));
    }
  }
}