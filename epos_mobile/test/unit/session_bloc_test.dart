// test/unit/session_bloc_test.dart
// ================================================
// Tests unitaires de SessionBloc — chargement, rafraîchissement, erreurs, et
// les getters calculés de SessionLoaded (sessionsEnCours, planning, etc.).
//
// #197 — SessionBloc s'abonne aussi à WebSocketService.instance.onLotStatusUpdate
// pour se rafraîchir en live. Ce comportement dépend d'un VRAI flux STOMP et
// n'est PAS exercé ici : le voir testé nécessiterait d'exposer un point
// d'injection sur WebSocketService (singleton), ce qui n'existe pas encore.
// Voir la liste des tests futurs recommandés en fin de session pour ce point.

import 'package:flutter_test/flutter_test.dart';

import 'package:epos_mobile/features/home/domain/entities/session.dart';
import 'package:epos_mobile/features/home/domain/repositories/session_repository.dart';
import 'package:epos_mobile/features/home/presentation/bloc/session_bloc.dart';

// ── Doublure de repository ──────────────────────────────────────────────────
class _FakeSessionRepository implements SessionRepository {
  _FakeSessionRepository({
    this.sessions = const [],
    this.stats = const EvaluateurStats(
      sessionsAssignees: 0, totalEtudiants: 0, lotsValides: 0, totalLots: 0,
    ),
    this.planning = const [],
    this.clockOffset = Duration.zero,
    this.shouldThrow = false,
  });

  List<Session> sessions;
  EvaluateurStats stats;
  List<PlanningCell> planning;
  Duration clockOffset;
  bool shouldThrow;
  int getSessionsCalls = 0;

  @override
  Future<List<Session>> getSessions() async {
    getSessionsCalls++;
    if (shouldThrow) throw Exception('Impossible de joindre le serveur.');
    return sessions;
  }

  @override
  Future<EvaluateurStats> getStats() async {
    if (shouldThrow) throw Exception('Impossible de joindre le serveur.');
    return stats;
  }

  @override
  Future<List<PlanningCell>> getPlanningDuJour() async {
    if (shouldThrow) throw Exception('Impossible de joindre le serveur.');
    return planning;
  }

  @override
  Future<Duration> getClockOffset() async => clockOffset;
}

const _sessionEnCours = Session(
  id: 1, stationId: 1, stationNom: 'Station 3 — Titrimétrie',
  matiere: 'Chimie Thérapeutique', annee: 'CT-2026',
  statut: SessionStatus.enCours, heureDebut: '09:42', nbEtudiants: 4,
  salle: 'Salle B3', lotActuel: 3, totalLots: 8,
);
const _sessionAVenir = Session(
  id: 2, stationId: 2, stationNom: 'Station 4 — Antipyrine',
  matiere: 'Chimie Thérapeutique', annee: 'CT-2026',
  statut: SessionStatus.aVenir, heureDebut: '10:00', nbEtudiants: 4,
  salle: 'Salle B4', lotActuel: 0, totalLots: 8,
);
const _sessionTerminee = Session(
  id: 3, stationId: 1, stationNom: 'Station 3 — Titrimétrie',
  matiere: 'Chimie Thérapeutique', annee: 'CT-2026',
  statut: SessionStatus.terminee, heureDebut: '08:00', heureFin: '09:30',
  nbEtudiants: 4, salle: 'Salle B3', lotActuel: 8, totalLots: 8,
);

Future<void> _settle() => Future<void>.delayed(const Duration(milliseconds: 60));

void main() {
  group('SessionLoadRequested', () {
    test('succès — SessionLoading puis SessionLoaded avec toutes les données',
            () async {
          final repo = _FakeSessionRepository(
            sessions: const [_sessionEnCours, _sessionAVenir],
            stats: const EvaluateurStats(
              sessionsAssignees: 2, totalEtudiants: 8, lotsValides: 1, totalLots: 8,
            ),
            planning: const [
              PlanningCell(heure: '09:00', lotNumero: 1, statut: CellStatus.termine),
            ],
            clockOffset: const Duration(seconds: 3),
          );
          final bloc = SessionBloc(repository: repo);
          final states = <SessionState>[];
          final sub = bloc.stream.listen(states.add);

          bloc.add(const SessionLoadRequested());
          await _settle();
          await sub.cancel();

          expect(states, [isA<SessionLoading>(), isA<SessionLoaded>()]);
          final loaded = states[1] as SessionLoaded;
          expect(loaded.sessions.length, 2);
          expect(loaded.stats.sessionsAssignees, 2);
          expect(loaded.planning.length, 1);
          expect(loaded.clockOffset, const Duration(seconds: 3));
          await bloc.close();
        });

    test('échec réseau → SessionLoading puis SessionError', () async {
      final repo = _FakeSessionRepository(shouldThrow: true);
      final bloc = SessionBloc(repository: repo);
      final states = <SessionState>[];
      final sub = bloc.stream.listen(states.add);

      bloc.add(const SessionLoadRequested());
      await _settle();
      await sub.cancel();

      expect(states, [isA<SessionLoading>(), isA<SessionError>()]);
      expect((states[1] as SessionError).message,
          contains('Impossible de charger les sessions'));
      await bloc.close();
    });

    test('état initial avant tout chargement est SessionInitial', () {
      final bloc = SessionBloc(repository: _FakeSessionRepository());
      expect(bloc.state, isA<SessionInitial>());
      bloc.close();
    });
  });

  group('SessionRefreshRequested', () {
    test('ne remet PAS le spinner (pas de SessionLoading) et rafraîchit les données',
            () async {
          final repo = _FakeSessionRepository(sessions: const [_sessionAVenir]);
          final bloc = SessionBloc(repository: repo);

          // Premier chargement.
          bloc.add(const SessionLoadRequested());
          await _settle();
          expect(bloc.state, isA<SessionLoaded>());

          // Les données changent côté serveur avant le refresh.
          repo.sessions = const [_sessionEnCours, _sessionAVenir, _sessionTerminee];

          final states = <SessionState>[];
          final sub = bloc.stream.listen(states.add);
          bloc.add(const SessionRefreshRequested());
          await _settle();
          await sub.cancel();

          expect(states, [isA<SessionLoaded>()],
              reason: 'un refresh ne doit jamais réafficher le spinner plein écran');
          expect((states.single as SessionLoaded).sessions.length, 3);
          await bloc.close();
        });

    test('un refresh en échec produit SessionError (même sans SessionLoading)',
            () async {
          final repo = _FakeSessionRepository(sessions: const [_sessionAVenir]);
          final bloc = SessionBloc(repository: repo);
          bloc.add(const SessionLoadRequested());
          await _settle();

          repo.shouldThrow = true;
          bloc.add(const SessionRefreshRequested());
          await _settle();

          expect(bloc.state, isA<SessionError>());
          await bloc.close();
        });
  });

  group('SessionLoaded — getters calculés', () {
    final loaded = SessionLoaded(
      sessions: const [_sessionEnCours, _sessionAVenir, _sessionTerminee],
      stats: const EvaluateurStats(
        sessionsAssignees: 3, totalEtudiants: 12, lotsValides: 1, totalLots: 8,
      ),
      planning: const [
        PlanningCell(heure: '10:00', lotNumero: 2, statut: CellStatus.aVenir),
        PlanningCell(heure: '09:00', lotNumero: 1, statut: CellStatus.termine),
        PlanningCell(heure: '09:00', lotNumero: 2, statut: CellStatus.aucun),
      ],
    );

    test('sessionsEnCours ne retient que le statut enCours', () {
      expect(loaded.sessionsEnCours, [_sessionEnCours]);
    });

    test('sessionsAVenir ne retient que le statut aVenir', () {
      expect(loaded.sessionsAVenir, [_sessionAVenir]);
    });

    test('heures — valeurs uniques, triées', () {
      expect(loaded.heures, ['09:00', '10:00']);
    });

    test('lots — valeurs uniques, triées', () {
      expect(loaded.lots, [1, 2]);
    });

    test('cellStatus renvoie le statut de la cellule correspondante', () {
      expect(loaded.cellStatus('09:00', 1), CellStatus.termine);
      expect(loaded.cellStatus('10:00', 2), CellStatus.aVenir);
    });

    test('cellStatus renvoie CellStatus.aucun pour une cellule absente', () {
      expect(loaded.cellStatus('11:00', 9), CellStatus.aucun);
    });
  });
}