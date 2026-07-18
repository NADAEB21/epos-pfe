// lib/features/home/presentation/screens/home_screen.dart

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/offline/offline_bloc.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/utils/app_translations.dart';
import '../../../auth/domain/entities/user.dart';
import '../../../auth/presentation/bloc/auth_bloc.dart';
import '../../../grading/domain/repositories/grading_repository.dart';
import '../../../grading/presentation/bloc/grading_bloc.dart';
import '../../../grading/presentation/screens/grading_screen.dart';
import '../../../profile/domain/entities/profile_settings.dart';
import '../../../profile/presentation/bloc/profile_bloc.dart';
import '../../../profile/presentation/screens/profile_screen.dart';
import '../../domain/entities/session.dart';
import '../bloc/session_bloc.dart';

// ================================================================
// SNIPPET : remplacer la fonction _naviguerVersNotation dans
// home_screen.dart par cette version corrigée.
//
// Correction point 3 :
//   - session.stationId → passé à GradingSessionStarted comme stationId
//                         → utilisé par getGrille(stationId) dans GradingBloc
//   - session.id        → passé comme lotId via session.lotActuel ou session.id
//                         → utilisé par getLot(stationId, lotNumero) dans GradingBloc
//
// Le GradingRepository.getLot(stationId, lotNumero) backend attend :
//   stationId  = session.stationId (pour retrouver l'évaluateur via sa rotation)
//   lotNumero  = session.lotActuel (numéro du lot courant dans la rotation)
// ================================================================

void _naviguerVersNotation(
  BuildContext context,
  Session session,
  GradingRepository gradingRepository,
) {
  final authBloc    = context.read<AuthBloc>();
  final profileBloc = context.read<ProfileBloc>();
  final sessionBloc = context.read<SessionBloc>();
  // BF6.2 — OfflineBloc injecté dans GradingBloc pour rafraîchir le badge
  final offlineBloc = context.read<OfflineBloc>();

  // final int stationId = session.stationId ?? session.id;
  final int stationId  = session.stationId ?? 0;
  final int rotationId = session.id;
  final int lotNumero = session.lotActuel > 0 ? session.lotActuel : 1;
  final debutCreneau  = _parseHeureDebut(session.heureDebut);
  final sessionState = sessionBloc.state;
  final clockOffset = sessionState is SessionLoaded ? sessionState.clockOffset : Duration.zero;

  final gradingBloc = GradingBloc(
     repository:  gradingRepository,
     offlineBloc: offlineBloc,
   )..add(GradingSessionStarted(
       rotationId:   rotationId,
       stationId:    stationId,
       lotNumero:    lotNumero,
       stationNom:   session.stationNom,
       dureeMinutes: session.dureeStationMin,
       debutCreneau: session.debutPrevu,
       // #196 (ADR-0012 / ADR-0009) — la session porte déjà ces deux
       // valeurs depuis le dashboard ; on les transmet à GradingBloc pour
       // piloter le compte à rebours / avertissement de fin de passage.
       avertissementLeadSec: session.avertissementLeadSec,
       enPause:              session.enPause,
       clockOffset:          clockOffset,
     ));

  Navigator.of(context, rootNavigator: true)
    .push(MaterialPageRoute(
      builder: (_) => MultiBlocProvider(
        providers: [
          BlocProvider.value(value: authBloc),
          BlocProvider.value(value: profileBloc),
          BlocProvider.value(value: sessionBloc),
          BlocProvider.value(value: gradingBloc),
          // BF6.2 — propagé dans GradingScreen pour ConnectivityBanner + badge
          BlocProvider.value(value: offlineBloc),
        ],
        child: GradingScreen(session: session),
      ),
    ))
    .then((_) {
      // FIX : BlocProvider.value ne dispose JAMAIS le bloc fourni. Sans ce
      // close() explicite, le Timer.periodic restait actif en arrière-plan
      // après un retour à l'accueil — fuite mémoire ET source de confusion
      // sur "quel minuteur est actif".
      gradingBloc.close();
      sessionBloc.add(const SessionRefreshRequested());
    });
}

/// Convertit "HH:mm" en DateTime du jour courant.
DateTime? _parseHeureDebut(String heureDebut) {
  try {
    final parts = heureDebut.split(':');
    if (parts.length != 2) return null;
    final now = DateTime.now();
    return DateTime(
      now.year, now.month, now.day,
      int.parse(parts[0]), int.parse(parts[1]),
    );
  } catch (_) {
    return null;
  }
}

// ── Navigation vers ProfileScreen ─────────────────────────────────────────────
void _naviguerVersProfil(BuildContext context) {
  final authBloc    = context.read<AuthBloc>();
  final profileBloc = context.read<ProfileBloc>();
  final sessionBloc = context.read<SessionBloc>();

  Navigator.of(context, rootNavigator: true).push(MaterialPageRoute(
    builder: (_) => MultiBlocProvider(
      providers: [
        BlocProvider.value(value: authBloc),
        BlocProvider.value(value: profileBloc),
        BlocProvider.value(value: sessionBloc),
      ],
      child: const ProfileScreen(),
    ),
  ));
}

// ── Couleurs adaptatives ──────────────────────────────────────────────────────
class _C {
  final bool isDark;
  const _C(this.isDark);
  Color get card        => isDark ? const Color(0xFF2E3D22) : AppTheme.surface;
  Color get cardAVenir  => isDark ? const Color(0xFF2A3820) : AppTheme.surface;
  Color get planning    => isDark ? const Color(0xFF263318) : AppTheme.surface;
  Color get border      => isDark ? const Color(0xFF4A6030) : const Color(0xFFEEEBE3);
  Color get textTitle   => isDark ? Colors.white            : AppTheme.textPrimary;
  Color get textSub     => isDark ? const Color(0xFFBBCCAA) : AppTheme.textSecondary;
  Color get textChip    => isDark ? const Color(0xFFAABB99) : AppTheme.textSecondary;
  Color get planBorder  => isDark ? const Color(0xFF3A4E28) : const Color(0xFFEEEBE3);
  Color get heureText   => isDark ? const Color(0xFFCCDDBB) : AppTheme.textPrimary;
  Color get sectionTitle => isDark ? Colors.white           : AppTheme.textPrimary;
  Color get badgeHeureBg => isDark
      ? AppTheme.scoreOrange.withValues(alpha: 0.20)
      : AppTheme.scoreOrange.withValues(alpha: 0.12);
}

// ════════════════════════════════════════════════
// HOME SCREEN
// Reçoit gradingRepository pour le transmettre à la navigation.
// ════════════════════════════════════════════════
class HomeScreen extends StatelessWidget {
  final GradingRepository gradingRepository;   // ← ajouté

  const HomeScreen({super.key, required this.gradingRepository});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<ProfileBloc, ProfileState>(
      builder: (context, profileState) {
        final lang   = profileState.settings.language;
        final isDark = profileState.settings.themeMode == AppThemeMode.dark;
        return BlocBuilder<AuthBloc, AuthState>(
          builder: (context, authState) {
            final user = authState is AuthAuthenticated ? authState.user : null;
            return Scaffold(
              backgroundColor: Theme.of(context).scaffoldBackgroundColor,
              body: BlocBuilder<SessionBloc, SessionState>(
                builder: (context, state) {
                  return RefreshIndicator(
                    color: AppTheme.primary,
                    onRefresh: () async {
                      context.read<SessionBloc>().add(const SessionRefreshRequested());
                      await Future.delayed(const Duration(milliseconds: 800));
                    },
                    child: CustomScrollView(
                      slivers: [
                        SliverToBoxAdapter(
                          child: _Header(user: user, state: state, lang: lang),
                        ),
                        SliverToBoxAdapter(
                          child: _Body(
                            state:             state,
                            lang:              lang,
                            isDark:            isDark,
                            gradingRepository: gradingRepository,  // ← transmis
                          ),
                        ),
                      ],
                    ),
                  );
                },
              ),
              bottomNavigationBar: _BottomNav(
                lang:              lang,
                gradingRepository: gradingRepository,  // ← transmis
              ),
            );
          },
        );
      },
    );
  }
}

// ════════════════════════════════════════════════
// HEADER
// ════════════════════════════════════════════════
class _Header extends StatelessWidget {
  final User?        user;
  final SessionState state;
  final AppLanguage  lang;
  const _Header({required this.user, required this.state, required this.lang});

  @override
  Widget build(BuildContext context) {
    final stats = state is SessionLoaded ? (state as SessionLoaded).stats : null;
    return Container(
      width: double.infinity,
      decoration: const BoxDecoration(
        color: AppTheme.primaryDark,
        borderRadius: BorderRadius.only(
          bottomLeft:  Radius.circular(24),
          bottomRight: Radius.circular(24),
        ),
      ),
      padding: EdgeInsets.only(
        top:    MediaQuery.of(context).padding.top + 16,
        left:   20, right: 20, bottom: 24,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    Tr.get(lang, 'home_bonjour'),
                    style: TextStyle(
                      color: Colors.white.withValues(alpha: 0.75), fontSize: 13,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    user != null
                        ? '${Tr.get(lang, 'home_dr')} ${user!.nomComplet}'
                        : '...',
                    style: const TextStyle(
                      color: Colors.white, fontSize: 18, fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
              GestureDetector(
                onTap: () => _naviguerVersProfil(context),
                child: Container(
                  width: 48, height: 48,
                  decoration: BoxDecoration(
                    color: AppTheme.accent, shape: BoxShape.circle,
                    border: Border.all(
                      color: Colors.white.withValues(alpha: 0.25), width: 2,
                    ),
                  ),
                  child: Center(
                    child: Text(
                      user?.initiales ?? '??',
                      style: const TextStyle(
                        color: Colors.white, fontSize: 17, fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          if (stats != null)
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceAround,
              children: [
                _StatChip(value: '${stats.sessionsAssignees}', label: Tr.get(lang, 'stat_sessions')),
                _StatDivider(),
                _StatChip(value: '${stats.totalEtudiants}',    label: Tr.get(lang, 'stat_etudiants'), valueColor: Colors.white),
                _StatDivider(),
                _StatChip(value: '${stats.lotsValides}/${stats.totalLots}', label: Tr.get(lang, 'stat_lots')),
              ],
            )
          else
            const Center(
              child: SizedBox(
                width: 24, height: 24,
                child: CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation(Colors.white54),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

class _StatChip extends StatelessWidget {
  final String value;
  final String label;
  final Color  valueColor;
  const _StatChip({required this.value, required this.label, this.valueColor = Colors.white});

  @override
  Widget build(BuildContext context) => Column(
    children: [
      Text(value, style: TextStyle(color: valueColor, fontSize: 22, fontWeight: FontWeight.w700)),
      const SizedBox(height: 2),
      Text(label, textAlign: TextAlign.center,
          style: TextStyle(color: Colors.white.withValues(alpha: 0.70), fontSize: 11, height: 1.3)),
    ],
  );
}

class _StatDivider extends StatelessWidget {
  @override
  Widget build(BuildContext context) => Container(
    height: 36, width: 1,
    color: Colors.white.withValues(alpha: 0.2),
  );
}

// ════════════════════════════════════════════════
// BODY
// ════════════════════════════════════════════════
class _Body extends StatelessWidget {
  final SessionState     state;
  final AppLanguage      lang;
  final bool             isDark;
  final GradingRepository gradingRepository;  // ← ajouté

  const _Body({
    required this.state,
    required this.lang,
    required this.isDark,
    required this.gradingRepository,
  });

  @override
  Widget build(BuildContext context) {
    if (state is SessionLoading || state is SessionInitial) {
      return const Padding(
        padding: EdgeInsets.only(top: 80),
        child: Center(child: CircularProgressIndicator(color: AppTheme.primary)),
      );
    }
    if (state is SessionError) {
      return Padding(
        padding: const EdgeInsets.all(32),
        child: Center(
          child: Text(
            (state as SessionError).message,
            textAlign: TextAlign.center,
            style: const TextStyle(color: AppTheme.scoreRed),
          ),
        ),
      );
    }

    final loaded            = state as SessionLoaded;
    final hasSessionEnCours = loaded.sessionsEnCours.isNotEmpty;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (hasSessionEnCours) ...[
            _SessionEnCoursCard(
              session:           loaded.sessionsEnCours.first,
              lang:              lang,
              isDark:            isDark,
              gradingRepository: gradingRepository,
            ),
            const SizedBox(height: 24),
          ] else ...[
            _AucuneSessionCard(lang: lang, isDark: isDark),
            const SizedBox(height: 24),
          ],
          if (loaded.sessionsAVenir.isNotEmpty) ...[
            _SectionTitle(icon: '📍', title: Tr.get(lang, 'home_a_venir')),
            const SizedBox(height: 10),
            ...loaded.sessionsAVenir.map(
              (s) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: _SessionAVenirCard(session: s, isDark: isDark),
              ),
            ),
            const SizedBox(height: 24),
          ],
          _SectionTitle(icon: '📋', title: Tr.get(lang, 'home_planning')),
          const SizedBox(height: 10),
          _PlanningTable(loaded: loaded, lang: lang),
          const SizedBox(height: 12),
          _PlanningLegend(lang: lang),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

// ════════════════════════════════════════════════
// CARD — Session EN COURS
// ════════════════════════════════════════════════
class _SessionEnCoursCard extends StatelessWidget {
  final Session          session;
  final AppLanguage      lang;
  final bool             isDark;
  final GradingRepository gradingRepository;

  const _SessionEnCoursCard({
    required this.session,
    required this.lang,
    required this.isDark,
    required this.gradingRepository,
  });

  @override
  Widget build(BuildContext context) {
    final c = _C(isDark);
    return GestureDetector(
      onTap: () => _naviguerVersNotation(context, session, gradingRepository),
      child: Container(
        decoration: BoxDecoration(
          color: c.card,
          borderRadius: BorderRadius.circular(14),
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: isDark ? 0.20 : 0.06),
              blurRadius: 12, offset: const Offset(0, 4),
            ),
          ],
          border: Border.all(color: c.border),
        ),
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(session.stationNom,
                          style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700, color: c.textTitle)),
                          
                      if (_matiereAnneeLabel(session) case final label?) ...[
                        const SizedBox(height: 2),
                        Text(label, style: TextStyle(fontSize: 12, color: c.textSub)),
                      ],
                    ],
                  ),
                ),
                _BadgeEnCours(lang: lang),
              ],
            ),
            const SizedBox(height: 14),
            Divider(height: 1, color: c.border),
            const SizedBox(height: 14),
            Row(
              children: [
                _InfoChip(icon: Icons.access_time_outlined,  label: session.heureDebut),
                const SizedBox(width: 16),
                _InfoChip(icon: Icons.group_outlined,        label: '${session.nbEtudiants} ét.'),
                const SizedBox(width: 16),
                _InfoChip(icon: Icons.location_on_outlined,  label: session.salle ?? 'Salle N/A'),
              ],
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: ElevatedButton.icon(
                onPressed: () => _naviguerVersNotation(context, session, gradingRepository),
                icon: const Icon(Icons.edit_outlined, size: 18),
                label: Text(Tr.get(lang, 'home_reprendre')),
                style: ElevatedButton.styleFrom(
                  backgroundColor: AppTheme.primary,
                  foregroundColor: Colors.white,
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                  textStyle: const TextStyle(fontSize: 14, fontWeight: FontWeight.w600),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _BadgeEnCours extends StatelessWidget {
  final AppLanguage lang;
  const _BadgeEnCours({required this.lang});

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
    decoration: BoxDecoration(
      color: AppTheme.scoreGreen.withValues(alpha: 0.15),
      borderRadius: BorderRadius.circular(20),
      border: Border.all(color: AppTheme.scoreGreen.withValues(alpha: 0.4)),
    ),
    child: Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 6, height: 6,
          decoration: const BoxDecoration(color: AppTheme.scoreGreen, shape: BoxShape.circle),
        ),
        const SizedBox(width: 5),
        Text(
          Tr.get(lang, 'home_en_cours'),
          style: const TextStyle(fontSize: 10, fontWeight: FontWeight.w700,
              color: AppTheme.scoreGreen, letterSpacing: 0.5),
        ),
      ],
    ),
  );
}

// ════════════════════════════════════════════════
// CARD — Session À VENIR
// ════════════════════════════════════════════════
class _SessionAVenirCard extends StatelessWidget {
  final Session session;
  final bool    isDark;
  const _SessionAVenirCard({required this.session, required this.isDark});

  @override
  Widget build(BuildContext context) {
    final c = _C(isDark);
    return Container(
      decoration: BoxDecoration(
        color: c.cardAVenir, borderRadius: BorderRadius.circular(12),
        border: Border.all(color: c.border),
        boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: isDark ? 0.20 : 0.04), blurRadius: 8, offset: const Offset(0, 2))],
      ),
      padding: const EdgeInsets.all(14),
      child: Row(
        children: [
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: BoxDecoration(color: c.badgeHeureBg, borderRadius: BorderRadius.circular(8)),
            child: Text(session.heureDebut,
                style: const TextStyle(fontSize: 13, fontWeight: FontWeight.w700, color: AppTheme.scoreOrange)),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(session.stationNom,
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: c.textTitle)),
                if (_matiereAnneeLabel(session) case final label?) ...[
                  const SizedBox(height: 3),
                  Text(label, style: TextStyle(fontSize: 11, color: c.textSub)),
            ],
                const SizedBox(height: 6),
                Row(children: [
                  _InfoChip(icon: Icons.group_outlined,       label: '${session.nbEtudiants} ét.', small: true),
                  const SizedBox(width: 12),
                  _InfoChip(icon: Icons.location_on_outlined, label: session.salle ?? '-', small: true),
                ]),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ════════════════════════════════════════════════
// TABLEAU — Planning du jour
// ════════════════════════════════════════════════
class _PlanningTable extends StatelessWidget {
  final SessionLoaded loaded;
  final AppLanguage   lang;
  const _PlanningTable({required this.loaded, required this.lang});

  @override
  Widget build(BuildContext context) {
    final heures = loaded.heures;
    final lots   = loaded.lots;
    final isDarkPlan = Theme.of(context).brightness == Brightness.dark;
    final planBorder = _C(isDarkPlan).planBorder;

    return Container(
      decoration: BoxDecoration(
        color: _C(isDarkPlan).planning,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: planBorder),
        boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: isDarkPlan ? 0.20 : 0.04), blurRadius: 8, offset: const Offset(0, 2))],
      ),
      clipBehavior: Clip.hardEdge,
      child: Table(
        border: TableBorder(
          horizontalInside: BorderSide(color: planBorder, width: 0.8),
          verticalInside:   BorderSide(color: planBorder, width: 0.8),
        ),
        columnWidths: {
          0: const IntrinsicColumnWidth(),
          for (int i = 1; i <= lots.length; i++) i: const FlexColumnWidth(),
        },
        children: [
          TableRow(
            decoration: const BoxDecoration(color: AppTheme.primaryDark),
            children: [
              _HeaderCell(Tr.get(lang, 'legend_heure')),
              ...lots.map((l) => _HeaderCell('L$l')),
            ],
          ),
          ...heures.map((heure) => TableRow(
            children: [
              _HeureCell(heure),
              ...lots.map((lot) => _PlanningCell(status: loaded.cellStatus(heure, lot))),
            ],
          )),
        ],
      ),
    );
  }
}

class _HeaderCell extends StatelessWidget {
  final String text;
  const _HeaderCell(this.text);
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 12),
    child: Text(text, textAlign: TextAlign.center,
        style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.w600)),
  );
}

class _HeureCell extends StatelessWidget {
  final String heure;
  const _HeureCell(this.heure);
  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.symmetric(vertical: 12, horizontal: 10),
    child: Text(heure,
        style: TextStyle(fontSize: 12, fontWeight: FontWeight.w500,
            color: _C(Theme.of(context).brightness == Brightness.dark).heureText)),
  );
}

class _PlanningCell extends StatelessWidget {
  final CellStatus status;
  const _PlanningCell({required this.status});
  @override
  Widget build(BuildContext context) {
    switch (status) {
      case CellStatus.termine: return const _CellContent(icon: Icons.check, color: AppTheme.scoreGreen);
      case CellStatus.aVenir:  return const _CellContent(text: '»', color: AppTheme.scoreOrange);
      case CellStatus.aucun:   return const _CellContent(text: '—', color: AppTheme.criterionPending);
    }
  }
}

class _CellContent extends StatelessWidget {
  final IconData? icon;
  final String?   text;
  final Color     color;
  const _CellContent({this.icon, this.text, required this.color});
  @override
  Widget build(BuildContext context) => SizedBox(
    height: 40,
    child: Center(
      child: icon != null
          ? Icon(icon, color: color, size: 16)
          : Text(text ?? '', style: TextStyle(color: color, fontSize: 16, fontWeight: FontWeight.w600)),
    ),
  );
}

class _PlanningLegend extends StatelessWidget {
  final AppLanguage lang;
  const _PlanningLegend({required this.lang});
  @override
  Widget build(BuildContext context) => Row(
    children: [
      _LegendItem(color: AppTheme.scoreOrange,      text: Tr.get(lang, 'legend_a_venir')),
      const SizedBox(width: 16),
      _LegendItem(color: AppTheme.scoreGreen,       text: Tr.get(lang, 'legend_termine')),
      const SizedBox(width: 16),
      _LegendItem(color: AppTheme.criterionPending, text: Tr.get(lang, 'legend_aucun')),
    ],
  );
}

class _LegendItem extends StatelessWidget {
  final Color  color;
  final String text;
  const _LegendItem({required this.color, required this.text});
  @override
  Widget build(BuildContext context) => Text(text,
      style: TextStyle(fontSize: 12, color: color, fontWeight: FontWeight.w500));
}

// ════════════════════════════════════════════════
// BOTTOM NAVIGATION BAR
// ════════════════════════════════════════════════
class _BottomNav extends StatelessWidget {
  final AppLanguage      lang;
  final GradingRepository gradingRepository;   // ← ajouté

  const _BottomNav({required this.lang, required this.gradingRepository});

  @override
  Widget build(BuildContext context) {
    return BottomNavigationBar(
      currentIndex:        0,
      selectedItemColor:   AppTheme.primary,
      unselectedItemColor: AppTheme.textSecondary,
      backgroundColor:     Theme.of(context).cardColor,
      elevation:           8,
      type:                BottomNavigationBarType.fixed,
      selectedLabelStyle:   const TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
      unselectedLabelStyle: const TextStyle(fontSize: 11),
      items: [
        BottomNavigationBarItem(
          icon: const Icon(Icons.home_outlined), activeIcon: const Icon(Icons.home),
          label: Tr.get(lang, 'nav_accueil'),
        ),
        BottomNavigationBarItem(
          icon: const Icon(Icons.edit_outlined), activeIcon: const Icon(Icons.edit),
          label: Tr.get(lang, 'nav_notation'),
        ),
        BottomNavigationBarItem(
          icon: const Icon(Icons.person_outline), activeIcon: const Icon(Icons.person),
          label: Tr.get(lang, 'nav_profil'),
        ),
      ],
      onTap: (index) {
        if (index == 1) {
          final sessionState = context.read<SessionBloc>().state;
          if (sessionState is SessionLoaded && sessionState.sessionsEnCours.isNotEmpty) {
            _naviguerVersNotation(context, sessionState.sessionsEnCours.first, gradingRepository);
          } else {
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(
              content: Text(Tr.get(lang, 'home_no_session_snack')),
              backgroundColor: AppTheme.scoreOrange,
              behavior: SnackBarBehavior.floating,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
              margin: const EdgeInsets.all(16),
              duration: const Duration(seconds: 3),
            ));
          }
        }
        if (index == 2) _naviguerVersProfil(context);
      },
    );
  }
}

// ════════════════════════════════════════════════
// WIDGETS UTILITAIRES
// ════════════════════════════════════════════════
class _SectionTitle extends StatelessWidget {
  final String icon;
  final String title;
  const _SectionTitle({required this.icon, required this.title});
  @override
  Widget build(BuildContext context) => Row(
    children: [
      Text(icon, style: const TextStyle(fontSize: 16)),
      const SizedBox(width: 6),
      Text(title, style: TextStyle(fontSize: 15, fontWeight: FontWeight.w700,
          color: _C(Theme.of(context).brightness == Brightness.dark).sectionTitle)),
    ],
  );
}

class _InfoChip extends StatelessWidget {
  final IconData icon;
  final String   label;
  final bool     small;
  const _InfoChip({required this.icon, required this.label, this.small = false});
  @override
  Widget build(BuildContext context) => Row(
    mainAxisSize: MainAxisSize.min,
    children: [
      Icon(icon, size: small ? 12 : 14,
          color: _C(Theme.of(context).brightness == Brightness.dark).textChip),
      const SizedBox(width: 4),
      Text(label, style: TextStyle(fontSize: small ? 11 : 12,
          color: _C(Theme.of(context).brightness == Brightness.dark).textChip)),
    ],
  );
}

class _AucuneSessionCard extends StatelessWidget {
  final AppLanguage lang;
  final bool        isDark;
  const _AucuneSessionCard({required this.lang, required this.isDark});
  @override
  Widget build(BuildContext context) => Container(
    width: double.infinity,
    padding: const EdgeInsets.symmetric(vertical: 32, horizontal: 20),
    decoration: BoxDecoration(
      color: Theme.of(context).cardColor,
      borderRadius: BorderRadius.circular(14),
      border: Border.all(color: const Color(0xFFEEEBE3)),
      boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.04), blurRadius: 8, offset: const Offset(0, 2))],
    ),
    child: Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Container(
          width: 64, height: 64,
          decoration: BoxDecoration(
            color: AppTheme.primary.withValues(alpha: 0.08), shape: BoxShape.circle,
          ),
          child: const Icon(Icons.event_busy_outlined, color: AppTheme.primary, size: 30),
        ),
        const SizedBox(height: 16),
        Text(Tr.get(lang, 'home_no_session'),
            style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600, color: _C(isDark).textTitle)),
        const SizedBox(height: 6),
        Text(Tr.get(lang, 'home_no_session_msg'),
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 13, color: _C(isDark).textSub, height: 1.5)),
      ],
    ),
  );
}

/// Construit "matière · année" en ne gardant que les parties non vides.
/// Retourne null si rien à afficher — pas de "· null" ni de séparateur orphelin.
String? _matiereAnneeLabel(Session s) {
  final m = s.matiere?.trim();
  final a = s.annee?.trim();

  final parts = [
    if (m?.isNotEmpty == true) m,
    if (a?.isNotEmpty == true) a,
  ];
  return parts.isEmpty ? null : parts.join(' · ');
}