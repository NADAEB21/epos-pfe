// lib/features/home/data/repositories/mock_session_repository.dart

import '../../domain/entities/session.dart';
import '../../domain/repositories/session_repository.dart';

class MockSessionRepository implements SessionRepository {
  static const _delay = Duration(milliseconds: 800);

  @override
  Future<List<Session>> getSessions() async {
    await Future.delayed(_delay);
    return const [
      // Correction point 3 : stationId ajouté (ID exam-service simulé).
      // En mock : stationId = 1 correspond à la Station 3 — Titrimétrie
      // dont la grille est définie dans MockGradingRepository.

      Session(
        id:          1,          // lot.id (scoring-service)
        stationId:   1,          // station réelle (exam-service) ← ajouté
        stationNom:  'Station 3 — Titrimétrie',
        matiere:     'Chimie Thérapeutique',
        annee:       'CT-2026',
        statut:      SessionStatus.enCours,
        heureDebut:  '09:42',
        nbEtudiants: 4,
        salle:       'Salle B3',
        lotActuel:   3,
        totalLots:   8,
      ),
      Session(
        id:          2,          // lot.id
        stationId:   2,          // station réelle ← ajouté
        stationNom:  'Station 4 — Antipyrine',
        matiere:     'Chimie Thérapeutique',
        annee:       'CT-2026',
        statut:      SessionStatus.aVenir,
        heureDebut:  '10:00',
        nbEtudiants: 4,
        salle:       'Salle B4',
        lotActuel:   0,
        totalLots:   8,
      ),
      Session(
        id:          3,
        stationId:   1,          // même station, session précédente ← ajouté
        stationNom:  'Station 3 — Titrimétrie',
        matiere:     'Chimie Thérapeutique',
        annee:       'CT-2026',
        statut:      SessionStatus.terminee,
        heureDebut:  '08:00',
        heureFin:    '09:30',
        nbEtudiants: 4,
        salle:       'Salle B3',
        lotActuel:   8,
        totalLots:   8,
      ),
    ];
  }

  @override
  Future<EvaluateurStats> getStats() async {
    await Future.delayed(_delay);
    return const EvaluateurStats(
      sessionsAssignees: 3,
      totalEtudiants:    24,
      lotsValides:       6,
      totalLots:         8,
    );
  }

  @override
  Future<List<PlanningCell>> getPlanningDuJour() async {
    await Future.delayed(_delay);
    return const [
      PlanningCell(heure: '09:00', lotNumero: 1, statut: CellStatus.termine),
      PlanningCell(heure: '09:00', lotNumero: 2, statut: CellStatus.termine),
      PlanningCell(heure: '09:00', lotNumero: 3, statut: CellStatus.aucun),
      PlanningCell(heure: '09:00', lotNumero: 4, statut: CellStatus.aucun),
      PlanningCell(heure: '10:00', lotNumero: 1, statut: CellStatus.aucun),
      PlanningCell(heure: '10:00', lotNumero: 2, statut: CellStatus.aVenir),
      PlanningCell(heure: '10:00', lotNumero: 3, statut: CellStatus.aucun),
      PlanningCell(heure: '10:00', lotNumero: 4, statut: CellStatus.aVenir),
      PlanningCell(heure: '11:00', lotNumero: 1, statut: CellStatus.aucun),
      PlanningCell(heure: '11:00', lotNumero: 2, statut: CellStatus.aVenir),
      PlanningCell(heure: '11:00', lotNumero: 3, statut: CellStatus.aVenir),
      PlanningCell(heure: '11:00', lotNumero: 4, statut: CellStatus.aVenir),
      PlanningCell(heure: '14:00', lotNumero: 1, statut: CellStatus.aVenir),
      PlanningCell(heure: '14:00', lotNumero: 2, statut: CellStatus.aVenir),
      PlanningCell(heure: '14:00', lotNumero: 3, statut: CellStatus.aVenir),
      PlanningCell(heure: '14:00', lotNumero: 4, statut: CellStatus.aVenir),
    ];
  }

  @override
  Future<Duration> getClockOffset() async => Duration.zero;
}