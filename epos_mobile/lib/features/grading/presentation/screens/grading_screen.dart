// lib/features/grading/presentation/screens/grading_screen.dart
// ================================================
// BF6.1 — WebSocket : le badge OfflinePendingBadge dans l'AppBar affiche
//          les notes en attente et l'état de connexion temps réel.
// BF6.2 — Offline  : ConnectivityBanner enveloppe le contenu pour afficher
//          la bannière hors-ligne / sync en cours.
//          L'OfflineBloc est fourni par home_screen.dart (MultiBlocProvider).
// #160  — Hiérarchie critère / sous-critères : un ItemEvaluation avec
//          sousCriteres non-vide s'affiche comme 1 ligne d'en-tête (lecture
//          seule, sous-total calculé) + N lignes sous-critères saisissables.
//          Voir _GradingRow / _buildRows ci-dessous.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/offline/connectivity_banner.dart';
import '../../../../core/offline/offline_bloc.dart';
import '../../../../core/theme/app_theme.dart';
import '../../../../core/utils/score_utils.dart';
import '../../domain/entities/item_evaluation.dart';
import '../../domain/entities/notation.dart';
import '../../../home/domain/entities/session.dart';
import '../bloc/grading_bloc.dart';
import 'student_detail_screen.dart';
import '../widgets/passage_countdown_badge.dart';
import '../../../home/presentation/bloc/session_bloc.dart';

// ── Dimensions adaptatives ────────────────────────────────────────────────────
class _Layout {
  final double critereColWidth;
  final double studentColWidth;
  final double headerHeight;
  final double cellMinHeight;
  final bool   isTablet;

  const _Layout({
    required this.critereColWidth,
    required this.studentColWidth,
    required this.headerHeight,
    required this.cellMinHeight,
    required this.isTablet,
  });

  factory _Layout.of(BuildContext context, int nbEtudiants) {
    final width    = MediaQuery.of(context).size.width;
    final isTablet = width >= 600;

    if (isTablet) {
      const double critereWidth = 160.0;
      const double minStudentW  =  90.0;
      const double divider      =   1.0;
      final available           = width - critereWidth - divider;
      final studentW = (available / nbEtudiants).clamp(minStudentW, 160.0);
      return _Layout(
        critereColWidth: critereWidth,
        studentColWidth: studentW,
        headerHeight:    120.0,
        cellMinHeight:    80.0,
        isTablet:        true,
      );
    } else {
      return const _Layout(
        critereColWidth: 125.0,
        studentColWidth:  95.0,
        headerHeight:    110.0,
        cellMinHeight:    75.0,
        isTablet:        false,
      );
    }
  }

  bool needsHScroll(BuildContext context, int nbEtudiants) {
    final totalContent = critereColWidth + 1 + studentColWidth * nbEtudiants;
    return totalContent > MediaQuery.of(context).size.width;
  }
}

// ── Couleurs adaptatives ──────────────────────────────────────────────────────
class _GC {
  final bool d;
  const _GC(this.d);
  Color get bg        => d ? const Color(0xFF1A1F14) : AppTheme.background;
  Color get rowEven   => d ? const Color(0xFF1E2518) : AppTheme.background;
  Color get rowOdd    => d ? const Color(0xFF222D19) : const Color(0xFFF7F5EF);
  Color get rowHeader => d ? const Color(0xFF2A3320) : AppTheme.surface;
  Color get border    => d ? const Color(0xFF3A4E28) : const Color(0xFFEEEBE3);
  Color get textPrim  => d ? Colors.white             : AppTheme.textPrimary;
  Color get textSec   => d ? const Color(0xFFAABB99)  : AppTheme.textSecondary;
  Color get numFill   => d ? const Color(0xFF2C3822)  : AppTheme.surface;
  Color get numBorder => d ? const Color(0xFF4A6030)  : const Color(0xFFDDD8CC);
}

// ═══════════════════════════════════════════════════════════════════════════════
// MODÈLE DE LIGNES — hiérarchie critère / sous-critères (#160)
//
// Un ItemEvaluation qui a des sous-critères devient :
//   • 1 ligne d'en-tête (regroupement, lecture seule, sous-total affiché)
//   • N lignes sous-critères (indentées, saisissables comme un item normal)
// Un ItemEvaluation sans sous-critères reste une ligne unique, inchangée.
// ═══════════════════════════════════════════════════════════════════════════════
class _GradingRow {
  final ItemEvaluation displayItem;
  final bool           isHeader;      // ligne de regroupement, sans saisie
  final bool           isSousCritere; // ligne indentée sous un critère parent

  const _GradingRow({
    required this.displayItem,
    this.isHeader      = false,
    this.isSousCritere = false,
  });
}

List<_GradingRow> _buildRows(List<ItemEvaluation> items) {
  final rows = <_GradingRow>[];
  for (final item in items) {
    if (item.hasSousCriteres) {
      rows.add(_GradingRow(displayItem: item, isHeader: true));
      for (final enfant in item.sousCriteres) {
        rows.add(_GradingRow(displayItem: enfant, isSousCritere: true));
      }
    } else {
      rows.add(_GradingRow(displayItem: item));
    }
  }
  return rows;
}

// ═══════════════════════════════════════════════════════════════════════════════
// GRADING SCREEN
// ═══════════════════════════════════════════════════════════════════════════════
class GradingScreen extends StatelessWidget {
     final Session session;
     const GradingScreen({super.key, required this.session});

     @override
     Widget build(BuildContext context) {
       return ConnectivityBanner(
         child: Scaffold(
           backgroundColor:
           _GC(Theme.of(context).brightness == Brightness.dark).bg,
           // #196 — Relaie enPause depuis SessionBloc (déjà chargé/rafraîchi
           // par ailleurs, y compris via #197) vers GradingBloc, qui gèle son
           // compte à rebours en conséquence (ADR-0009). Ne déclenche AUCUN
           // appel réseau supplémentaire.
           body: BlocListener<SessionBloc, SessionState>(
             listenWhen: (prev, curr) {
               if (curr is! SessionLoaded) return false;
               final s = curr.sessions.where((s) => s.id == session.id);
               return s.isNotEmpty;
             },
             listener: (context, sessionState) {
               if (sessionState is! SessionLoaded) return;
               final matching = sessionState.sessions
                   .where((s) => s.id == session.id);
               if (matching.isEmpty) return;
               context
                   .read<GradingBloc>()
                   .add(GradingPauseStateUpdated(matching.first.enPause));
             },
             child: BlocConsumer<GradingBloc, GradingState>(
               listener: (context, state) {
                 if (state is GradingError) {
                   ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                     content:         Text(state.message),
                     backgroundColor: AppTheme.scoreRed,
                     behavior:        SnackBarBehavior.floating,
                   ));
                 }
                 // #248 — erreur NON fatale : l'écran de notation reste affiché (et les
                 // notes avec lui), le message passe en surimpression.
                 if (state is GradingLoaded && state.messageErreur != null) {
                   ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                     content:         Text(state.messageErreur!),
                     backgroundColor: AppTheme.scoreRed,
                     behavior:        SnackBarBehavior.floating,
                   ));
                 }
               },
               builder: (context, state) {
                 if (state is GradingLoaded) {
                   return _GradingView(state: state);
                 }
                 return const Center(
                   child: CircularProgressIndicator(color: AppTheme.primary),
                 );
               },
             ),
           ),
         ),
       );
     }
   }

// ═══════════════════════════════════════════════════════════════════════════════
// VUE PRINCIPALE
// ═══════════════════════════════════════════════════════════════════════════════
class _GradingView extends StatefulWidget {
  final GradingLoaded state;
  const _GradingView({required this.state});

  @override
  State<_GradingView> createState() => _GradingViewState();
}

class _GradingViewState extends State<_GradingView> {
  late final ScrollController _headerHScroll;
  late final ScrollController _bodyHScroll;
  late final ScrollController _footerHScroll;

  bool _syncing = false;

  void _syncFrom(ScrollController source, List<ScrollController> targets) {
    if (_syncing) return;
    _syncing = true;
    for (final t in targets) {
      if (t.hasClients && t.offset != source.offset) {
        t.jumpTo(source.offset);
      }
    }
    _syncing = false;
  }

  @override
  void initState() {
    super.initState();
    _headerHScroll = ScrollController();
    _bodyHScroll   = ScrollController();
    _footerHScroll = ScrollController();

    _headerHScroll.addListener(
            () => _syncFrom(_headerHScroll, [_bodyHScroll, _footerHScroll]));
    _bodyHScroll.addListener(
            () => _syncFrom(_bodyHScroll, [_headerHScroll, _footerHScroll]));
  }

  @override
  void dispose() {
    _headerHScroll.dispose();
    _bodyHScroll.dispose();
    _footerHScroll.dispose();
    super.dispose();
  }

   @override
   Widget build(BuildContext context) {
     final layout = _Layout.of(context, widget.state.lot.etudiants.length);

     return BlocListener<GradingBloc, GradingState>(
       // #196 — Retour haptique UNE SEULE FOIS au moment où l'avertissement
       // s'active (jamais à chaque tick, ni pendant la pause) : l'évaluateur
       // a "les mains prises" pendant la manipulation, le retour doit être
       // perceptible sans devoir regarder l'écran.
       listenWhen: (prev, curr) {
         if (prev is! GradingLoaded || curr is! GradingLoaded) return false;
         final avant = PassageCountdownStatus.compute(
           tempsRestant: prev.tempsRestant,
           avertissementLeadSec: prev.avertissementLeadSec,
           enPause: prev.enPause,
         ).avertissementActif;
         final apres = PassageCountdownStatus.compute(
           tempsRestant: curr.tempsRestant,
           avertissementLeadSec: curr.avertissementLeadSec,
           enPause: curr.enPause,
         ).avertissementActif;
         return !avant && apres;
       },
       listener: (context, state) {
         HapticFeedback.heavyImpact();
       },
       child: Column(
         children: [
           _GradingAppBar(state: widget.state),
           PassageWarningBanner(
             tempsRestant: widget.state.tempsRestant,
             avertissementLeadSec: widget.state.avertissementLeadSec,
             enPause: widget.state.enPause,
           ),
           _StickyStudentHeader(
             state:   widget.state,
             hScroll: _headerHScroll,
             layout:  layout,
           ),
           Expanded(
             child: _GradingBody(
               state:   widget.state,
               hScroll: _bodyHScroll,
               layout:  layout,
             ),
           ),
           _GradingFooter(
             state:   widget.state,
             hScroll: _footerHScroll,
             layout:  layout,
           ),
         ],
       ),
     );
   }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CORPS — grille de notation
// ═══════════════════════════════════════════════════════════════════════════════
class _GradingBody extends StatelessWidget {
  final GradingLoaded    state;
  final ScrollController hScroll;
  final _Layout          layout;

  const _GradingBody({
    required this.state,
    required this.hScroll,
    required this.layout,
  });

  @override
  Widget build(BuildContext context) {
    final gc        = _GC(Theme.of(context).brightness == Brightness.dark);
    final rows      = _buildRows(state.grille.items);
    final etudiants = state.lot.etudiants;

    return SingleChildScrollView(
      scrollDirection: Axis.vertical,
      child: Stack(
        children: [
          // ── 1. Grille scrollable horizontalement ──────────────────────────
          SingleChildScrollView(
            controller:      hScroll,
            scrollDirection: Axis.horizontal,
            physics:         const ClampingScrollPhysics(),
            child: IntrinsicWidth(
              child: Column(
                children: rows.asMap().entries.map((entry) {
                  final row = entry.value;
                  return IntrinsicHeight(
                    child: Row(
                      children: [
                        // Cellule fantôme : force la hauteur de la ligne
                        _CritereCell(
                          row:       row,
                          gc:        gc,
                          invisible: true,
                          width:     layout.critereColWidth,
                          minHeight: layout.cellMinHeight,
                        ),
                        ...etudiants.map(
                              (e) => Container(
                            width: layout.studentColWidth,
                            decoration: BoxDecoration(
                              color: entry.key.isEven ? gc.rowEven : gc.rowOdd,
                              border: Border(
                                left:   BorderSide(color: gc.border, width: 0.5),
                                bottom: BorderSide(color: gc.border, width: 0.8),
                              ),
                            ),
                            child: row.isHeader
                            // #160 — ligne de regroupement : pas de saisie
                            // directe, on affiche le sous-total calculé
                            // (= somme des sous-critères déjà cochés).
                                ? _CritereSousTotalCell(
                              item:      row.displayItem,
                              notations: state.notations[e.id] ?? const {},
                              gc:        gc,
                            )
                                : _NotationCell(
                              etudiant:  e,
                              item:      row.displayItem,
                              notation:  state.notations[e.id]?[row.displayItem.id],
                              estValide: state.etudiantsValides.contains(e.id),
                            ),
                          ),
                        ),
                      ],
                    ),
                  );
                }).toList(),
              ),
            ),
          ),

          // ── 2. Overlay Critère fixe (ne scroll pas horizontalement) ───────
          Positioned(
            left: 0, top: 0, bottom: 0,
            child: Container(
              width: layout.critereColWidth,
              decoration: BoxDecoration(
                color: gc.bg,
                boxShadow: [
                  BoxShadow(
                    color:      Colors.black.withOpacity(0.10),
                    blurRadius: 4,
                    offset:     const Offset(2, 0),
                  ),
                ],
              ),
              child: Column(
                children: rows.asMap().entries.map((entry) {
                  return _CritereCell(
                    row:       entry.value,
                    gc:        gc,
                    index:     entry.key,
                    width:     layout.critereColWidth,
                    minHeight: layout.cellMinHeight,
                  );
                }).toList(),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CritereCell extends StatelessWidget {
  final _GradingRow row;
  final _GC         gc;
  final int?        index;
  final bool        invisible;
  final double      width;
  final double      minHeight;

  const _CritereCell({
    required this.row,
    required this.gc,
    this.index,
    this.invisible = false,
    required this.width,
    required this.minHeight,
  });

  @override
  Widget build(BuildContext context) {
    final item = row.displayItem;
    final bg = invisible
        ? Colors.transparent
        : (index! % 2 == 0 ? gc.rowEven : gc.rowOdd);

    final String libelleAffiche = row.isHeader
        ? '${item.libelle} :'
        : row.isSousCritere
        ? '•  ${item.libelle}'
        : item.libelle;

    return Container(
      width:       width,
      constraints: BoxConstraints(minHeight: minHeight),
      // Les sous-critères sont indentés pour matérialiser la hiérarchie.
      padding: EdgeInsets.fromLTRB(row.isSousCritere ? 20 : 10, 10, 10, 10),
      decoration:  BoxDecoration(
        color:  bg,
        border: Border(bottom: BorderSide(color: gc.border, width: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment:  MainAxisAlignment.center,
        mainAxisSize:       MainAxisSize.min,
        children: [
          Text(
            libelleAffiche,
            style: TextStyle(
              fontSize:   row.isSousCritere ? 10.5 : 11,
              fontWeight: row.isHeader ? FontWeight.w700 : FontWeight.normal,
              color:      invisible ? Colors.transparent : gc.textPrim,
              height:     1.3,
            ),
          ),
          // La ligne d'en-tête n'a pas de pondération propre (elle égale la
          // somme de ses sous-critères) : on n'affiche le badge que pour les
          // lignes réellement notables (item simple ou sous-critère).
          if (!row.isHeader) ...[
            const SizedBox(height: 4),
            Text(
              '${item.ponderation.toInt()} pts',
              style: TextStyle(
                fontSize:   10,
                color:      invisible ? Colors.transparent : AppTheme.primary,
                fontWeight: FontWeight.bold,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// #160 — Sous-total en lecture seule affiché sur la ligne d'en-tête d'un
/// critère à sous-critères : somme des sous-critères déjà cochés / pondération
/// totale du critère. Calculé via ScoreUtils.scoreCritere (aucune saisie ici).
class _CritereSousTotalCell extends StatelessWidget {
  final ItemEvaluation      item;
  final Map<int, Notation>  notations;
  final _GC                 gc;

  const _CritereSousTotalCell({
    required this.item,
    required this.notations,
    required this.gc,
  });

  @override
  Widget build(BuildContext context) {
    final sousTotal = ScoreUtils.scoreCritere(item: item, notations: notations);
    return Center(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
        decoration: BoxDecoration(
          color:        gc.numFill,
          borderRadius: BorderRadius.circular(8),
          border:       Border.all(color: gc.numBorder),
        ),
        child: Text(
          '${sousTotal.toStringAsFixed(sousTotal == sousTotal.truncateToDouble() ? 0 : 1)}'
              '/${item.ponderation.toInt()}',
          style: TextStyle(fontSize: 11, fontWeight: FontWeight.bold, color: gc.textPrim),
        ),
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FOOTER — scores + boutons
// ═══════════════════════════════════════════════════════════════════════════════
class _GradingFooter extends StatelessWidget {
  final GradingLoaded    state;
  final ScrollController hScroll;
  final _Layout          layout;

  const _GradingFooter({
    required this.state,
    required this.hScroll,
    required this.layout,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color:        AppTheme.primaryDark,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: EdgeInsets.fromLTRB(
        0, 14, 0,
        MediaQuery.of(context).padding.bottom + 14,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          // ── Ligne des scores ───────────────────────────────────────────────
          Row(
            children: [
              SizedBox(
                width: layout.critereColWidth,
                child: const Padding(
                  padding: EdgeInsets.only(left: 16),
                  child:   Text(
                    'Score /20',
                    style: TextStyle(
                      color:      Colors.white70,
                      fontSize:   13,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
              ),
              Expanded(
                child: SingleChildScrollView(
                  controller:      hScroll,
                  scrollDirection: Axis.horizontal,
                  physics:         const NeverScrollableScrollPhysics(),
                  child: Row(
                    children: state.lot.etudiants.map((e) {
                      final s = state.scoreEtudiant(e.id);
                      // BF6.1 — indicateur que le score vient du serveur
                      final isWsScore = state.wsScores.containsKey(e.id);
                      return SizedBox(
                        width: layout.studentColWidth,
                        child: Center(
                          child: Stack(
                            clipBehavior: Clip.none,
                            children: [
                              Container(
                                width:   65,
                                padding: const EdgeInsets.symmetric(vertical: 6),
                                decoration: BoxDecoration(
                                  color:        AppTheme.scoreColor(s),
                                  borderRadius: BorderRadius.circular(8),
                                ),
                                child: Text(
                                  s.toStringAsFixed(
                                    s == s.truncateToDouble() ? 0 : 1,
                                  ),
                                  textAlign: TextAlign.center,
                                  style: const TextStyle(
                                    color:      Colors.white,
                                    fontWeight: FontWeight.bold,
                                    fontSize:   15,
                                  ),
                                ),
                              ),
                              // Petit point vert = score synchronisé (BF6.1)
                              if (isWsScore)
                                Positioned(
                                  top:   -3,
                                  right: -3,
                                  child: Container(
                                    width:  8,
                                    height: 8,
                                    decoration: const BoxDecoration(
                                      color: AppTheme.scoreGreen,
                                      shape: BoxShape.circle,
                                    ),
                                  ),
                                ),
                            ],
                          ),
                        ),
                      );
                    }).toList(),
                  ),
                ),
              ),
            ],
          ),

          const SizedBox(height: 15),

          // ── #248 — Fin de vague : attente explicite, pas un cul-de-sac ──────
          // Sous ADR-0014-B l'avancement lot→lot appartient au responsable : quand
          // l'évaluateur a fini le dernier passage de sa station, il n'a pas « plus
          // rien à faire », il ATTEND quelqu'un. Le dire est ce qui distingue une
          // attente normale d'un écran mort (cf. #238).
          if (state.vagueTerminee)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: AppTheme.accent.withOpacity(0.15),
                  borderRadius: BorderRadius.circular(10),
                  border: Border.all(color: AppTheme.accent),
                ),
                child: const Row(
                  children: [
                    Icon(Icons.hourglass_top, size: 18, color: AppTheme.accent),
                    SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'Vague terminée pour cette station. '
                        'En attente de l’ouverture du lot suivant par le responsable.',
                        style: TextStyle(color: Colors.white, fontSize: 13),
                      ),
                    ),
                  ],
                ),
              ),
            ),

          // ── Boutons Valider lot / Lot suivant ──────────────────────────────
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: state.lotEnCoursDeValidation
                        ? null
                        : () => _confirmerValidation(context),
                    icon:  const Icon(Icons.check, size: 18),
                    label: const Text('Valider groupe'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: Colors.white,
                      side:    const BorderSide(color: Colors.white54),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape:   RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton.icon(
                    // #248 — actif si et seulement si le BACKEND annonce un passage
                    // suivant à cette station pour ce lot (calculé sur ordrePassage).
                    // L'ancienne garde `lot.numero >= lot.total` comparait le numéro du
                    // GROUPE au nombre de groupes : le carré latin faisant tourner les
                    // groupes, elle était vraie à l'envers — grisée au premier passage,
                    // active au dernier, où le clic effaçait l'écran.
                    onPressed: (!state.lot.groupeSuivantDisponible ||
                                state.lotEnCoursDeValidation)
                        ? null
                        : () => _confirmerGroupeSuivant(context),
                    icon:  const Icon(Icons.arrow_forward, size: 18),
                    label: const Text('Groupe suivant'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.accent,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(10),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  void _confirmerValidation(BuildContext context) {
    final nonValides = state.lot.etudiants
        .where((e) => !state.etudiantsValides.contains(e.id))
        .toList();
    if (nonValides.isNotEmpty) {
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title:   const Text('Étudiants non validés'),
          content: Text(
            '${nonValides.length} étudiant(s) restants. Valider quand même ?',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Annuler'),
            ),
            ElevatedButton(
              onPressed: () {
                Navigator.pop(context);
                context.read<GradingBloc>().add(const GradingGroupeValide());
              },
              child: const Text('Confirmer'),
            ),
          ],
        ),
      );
    } else {
      context.read<GradingBloc>().add(const GradingGroupeValide());
    }
  }

  void _confirmerGroupeSuivant(BuildContext context) {
    if (!state.tousLesEtudiantsValides) {
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title:   const Text('Groupe suivant ?'),
          content: const Text(
            'Certains étudiants ne sont pas validés. Voulez-vous continuer ?',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(context),
              child: const Text('Annuler'),
            ),
            ElevatedButton(
              onPressed: () {
                Navigator.pop(context);
                context
                    .read<GradingBloc>()
                    .add(const GradingGroupeSuivantDemande());
              },
              child: const Text('Continuer'),
            ),
          ],
        ),
      );
    } else {
      context.read<GradingBloc>().add(const GradingGroupeSuivantDemande());
    }
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CELLULE DE NOTATION
//
// Réutilisée telle quelle pour les items simples ET pour les sous-critères
// (#160) : un sous-critère est un ItemEvaluation comme un autre, donc les
// events GradingBinaryUpdated / GradingNumericUpdated fonctionnent sans
// modification du bloc.
// ═══════════════════════════════════════════════════════════════════════════════
class _NotationCell extends StatelessWidget {
  final dynamic        etudiant;
  final ItemEvaluation item;
  final Notation?      notation;
  final bool           estValide;

  const _NotationCell({
    required this.etudiant,
    required this.item,
    this.notation,
    required this.estValide,
  });

  @override
  Widget build(BuildContext context) {
    if (item.type == TypeCritere.binaire) {
      final bool? fait =
      notation == null ? null : notation!.valeur == 1.0;
      return Center(
        child: GestureDetector(
          onTap: estValide
              ? null
              : () {
            final bool? next = fait == null
                ? true
                : fait == true
                ? false
                : null;
            context.read<GradingBloc>().add(GradingBinaryUpdated(
              etudiantId: etudiant.id,
              itemId:     item.id,
              fait:       next,
            ));
          },
          child: Container(
            width:  38,
            height: 38,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: fait == null
                  ? Colors.grey.withOpacity(0.1)
                  : fait
                  ? Colors.green.withOpacity(0.2)
                  : Colors.red.withOpacity(0.2),
              border: Border.all(
                color: fait == null
                    ? Colors.grey
                    : fait
                    ? Colors.green
                    : Colors.red,
                width: 1.5,
              ),
            ),
            child: fait == null
                ? null
                : Icon(
              fait ? Icons.check : Icons.close,
              size:  20,
              color: fait ? Colors.green : Colors.red,
            ),
          ),
        ),
      );
    }

    return _NumericCell(
      etudiant:  etudiant,
      item:      item,
      notation:  notation,
      estValide: estValide,
    );
  }
}

// ── Cellule numérique ─────────────────────────────────────────────────────────
class _NumericCell extends StatefulWidget {
  final dynamic        etudiant;
  final ItemEvaluation item;
  final Notation?      notation;
  final bool           estValide;

  const _NumericCell({
    required this.etudiant,
    required this.item,
    required this.notation,
    required this.estValide,
  });

  @override
  State<_NumericCell> createState() => _NumericCellState();
}

class _NumericCellState extends State<_NumericCell> {
  late final TextEditingController _ctrl;

  String _fmt(double v) {
    if (v == v.truncateToDouble()) return v.toInt().toString();
    return v.toString()
        .replaceAll(RegExp(r'0+$'), '')
        .replaceAll(RegExp(r'\.$'), '');
  }

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(
      text: widget.notation != null ? _fmt(widget.notation!.valeur) : '',
    );
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final gc = _GC(Theme.of(context).brightness == Brightness.dark);
    return Center(
      child: SizedBox(
        width: 55,
        child: TextField(
          controller:      _ctrl,
          enabled:         !widget.estValide,
          textAlign:       TextAlign.center,
          keyboardType:
          const TextInputType.numberWithOptions(decimal: true),
          inputFormatters: [
            _DecimalFormatter(max: widget.item.valeurMax),
          ],
          style: TextStyle(
            fontSize:   14,
            fontWeight: FontWeight.bold,
            color:      gc.textPrim,
          ),
          decoration: InputDecoration(
            contentPadding: EdgeInsets.zero,
            isDense:        true,
            filled:         true,
            fillColor:      gc.numFill,
            hintText:       '—',
            hintStyle: TextStyle(color: gc.textSec, fontSize: 14),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide:   BorderSide(color: gc.numBorder),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide:   BorderSide(color: gc.numBorder),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: const BorderSide(color: AppTheme.primary, width: 2),
            ),
          ),
          onChanged: (val) {
            if (val.trim().isEmpty || val == '.') {
              context.read<GradingBloc>().add(GradingNumericUpdated(
                etudiantId: widget.etudiant.id,
                itemId:     widget.item.id,
                valeur:     0,
              ));
              return;
            }
            final parsed = double.tryParse(val);
            if (parsed != null) {
              context.read<GradingBloc>().add(GradingNumericUpdated(
                etudiantId: widget.etudiant.id,
                itemId:     widget.item.id,
                valeur:     parsed,
              ));
            }
          },
        ),
      ),
    );
  }
}

class _DecimalFormatter extends TextInputFormatter {
  final double max;
  _DecimalFormatter({required this.max});

  @override
  TextEditingValue formatEditUpdate(
      TextEditingValue oldValue,
      TextEditingValue newValue,
      ) {
    final normalized = newValue.text
        .replaceAll(',', '.')
        .replaceAll('\u060C', '.')
        .replaceAll('\u066B', '.');
    if (!RegExp(r'^\d*\.?\d*$').hasMatch(normalized)) return oldValue;
    final parsed = double.tryParse(normalized);
    if (parsed != null && parsed > max) return oldValue;
    return newValue.copyWith(
      text:      normalized,
      selection: TextSelection.collapsed(offset: normalized.length),
    );
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EN-TÊTE AVATARS (fixe, scroll synchro avec la grille)
// ═══════════════════════════════════════════════════════════════════════════════
class _StickyStudentHeader extends StatelessWidget {
  final GradingLoaded    state;
  final ScrollController hScroll;
  final _Layout          layout;

  const _StickyStudentHeader({
    required this.state,
    required this.hScroll,
    required this.layout,
  });

  @override
  Widget build(BuildContext context) {
    final gc        = _GC(Theme.of(context).brightness == Brightness.dark);
    final etudiants = state.lot.etudiants;

    return Container(
      decoration: BoxDecoration(
        color: gc.rowHeader,
        border: Border(
          bottom: BorderSide(color: gc.border, width: 0.8),
        ),
        boxShadow: [
          BoxShadow(
            color:      Colors.black.withOpacity(
              Theme.of(context).brightness == Brightness.dark ? 0.3 : 0.08,
            ),
            blurRadius: 4,
            offset:     const Offset(0, 2),
          ),
        ],
      ),
      height: layout.headerHeight,
      child: Row(
        children: [
          Container(
            width:     layout.critereColWidth,
            padding:   const EdgeInsets.symmetric(horizontal: 12),
            alignment: Alignment.centerLeft,
            color:     gc.rowHeader,
            child: Text(
              'Critère',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color:      gc.textSec,
                fontSize:   13,
              ),
            ),
          ),
          Expanded(
            child: SingleChildScrollView(
              controller:      hScroll,
              scrollDirection: Axis.horizontal,
              physics:         const ClampingScrollPhysics(),
              child: Row(
                children: etudiants
                    .map((e) => SizedBox(
                  width:  layout.studentColWidth,
                  height: layout.headerHeight,
                  child:  _EtudiantHeader(
                    etudiant:  e,
                    estValide: state.etudiantsValides.contains(e.id),
                    state:     state,
                  ),
                ))
                    .toList(),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _EtudiantHeader extends StatelessWidget {
  final dynamic        etudiant;
  final bool           estValide;
  final GradingLoaded  state;

  const _EtudiantHeader({
    required this.etudiant,
    required this.estValide,
    required this.state,
  });

  @override
  Widget build(BuildContext context) {
    final gc = _GC(Theme.of(context).brightness == Brightness.dark);
    return GestureDetector(
      onTap: () => Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => BlocProvider.value(
          value: context.read<GradingBloc>(),
          child: StudentDetailScreen(
            etudiant:   etudiant,
            stationNom: state.stationNom,
          ),
        ),
      )),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          CircleAvatar(
            radius:          20,
            backgroundColor: estValide
                ? AppTheme.scoreGreen
                : AppTheme.primaryDark,
            child: Text(
              etudiant.initiales,
              style: const TextStyle(
                color:      Colors.white,
                fontSize:   13,
                fontWeight: FontWeight.bold,
              ),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            etudiant.prenom,
            style: TextStyle(
              fontSize:   10,
              fontWeight: FontWeight.w600,
              color:      gc.textPrim,
            ),
            overflow: TextOverflow.ellipsis,
          ),
          Text(
            etudiant.nom,
            style: TextStyle(fontSize: 9, color: gc.textSec),
            overflow: TextOverflow.ellipsis,
          ),
        ],
      ),
    );
  }
}

// ═══════════════════════════════════════════════════════════════════════════════
// APPBAR — BF6.1 : badge offline + état WebSocket
// ═══════════════════════════════════════════════════════════════════════════════
class _GradingAppBar extends StatelessWidget {
  final GradingLoaded state;
  const _GradingAppBar({required this.state});

  @override
  Widget build(BuildContext context) {
    return Container(
      color:   AppTheme.primaryDark,
      padding: EdgeInsets.only(
        top:    MediaQuery.of(context).padding.top + 8,
        bottom: 12,
      ),
      child: Column(
        children: [
          Row(
            children: [
              IconButton(
                icon: const Icon(
                  Icons.arrow_back_ios_new,
                  color: Colors.white,
                  size:  18,
                ),
                onPressed: () =>
                    Navigator.of(context, rootNavigator: true).pop(),
              ),
              Expanded(
                child: Text(
                  state.stationNom,
                  style: const TextStyle(
                    color:      Colors.white,
                    fontSize:   17,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
              // BF6.2 — Badge : notations en attente de synchronisation
              const OfflinePendingBadge(),
            ],
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                _badge(state.lot.label, bold: true),
                const SizedBox(width: 8),
                _badge('${state.lot.etudiants.length} étudiants'),
                const Spacer(),
                // BF6.1 — Indicateur de connexion WebSocket temps réel
                _WsStatusBadge(),
                const SizedBox(width: 8),
                PassageCountdownBadge(
                   tempsRestant: state.tempsRestant,
                   avertissementLeadSec: state.avertissementLeadSec,
                   enPause: state.enPause,
                 ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _badge(String t, {bool bold = false}) => Container(
    padding:    const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
    decoration: BoxDecoration(
      color:        Colors.white12,
      borderRadius: BorderRadius.circular(20),
    ),
    child: Text(
      t,
      style: TextStyle(
        color:      Colors.white,
        fontSize:   12,
        fontWeight: bold ? FontWeight.bold : FontWeight.normal,
      ),
    ),
  );
}

/// BF6.1 — Indicateur compact de l'état WebSocket dans l'AppBar.
class _WsStatusBadge extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return BlocBuilder<OfflineBloc, OfflineState>(
      buildWhen: (prev, curr) => prev.isOnline != curr.isOnline,
      builder: (context, offlineState) {
        if (offlineState.isOnline) {
          // En ligne → WebSocket actif (point vert pulsant)
          return const _PulsingDot(color: AppTheme.scoreGreen);
        }
        // Hors-ligne → WebSocket inactif (point gris)
        return const _PulsingDot(color: Colors.white38, pulse: false);
      },
    );
  }
}

class _PulsingDot extends StatefulWidget {
  final Color color;
  final bool  pulse;

  const _PulsingDot({required this.color, this.pulse = true});

  @override
  State<_PulsingDot> createState() => _PulsingDotState();
}

class _PulsingDotState extends State<_PulsingDot>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ctrl;
  late final Animation<double>   _anim;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync:    this,
      duration: const Duration(milliseconds: 1200),
    );
    _anim = Tween<double>(begin: 0.5, end: 1.0).animate(
      CurvedAnimation(parent: _ctrl, curve: Curves.easeInOut),
    );
    if (widget.pulse) _ctrl.repeat(reverse: true);
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
      builder: (_, __) => Container(
        width:  8,
        height: 8,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: widget.color.withOpacity(
            widget.pulse ? _anim.value : 1.0,
          ),
        ),
      ),
    );
  }
}