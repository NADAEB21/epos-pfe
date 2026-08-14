// lib/features/grading/presentation/screens/student_detail_screen.dart
// ================================================
// Écran détail étudiant — fidèle au Figma :
//   • Header vert foncé : nom étudiant + station + score
//   • Toggle Présent / Absent
//   • Liste des critères avec score par item
//   • Zone Remarque
//   • Bouton "Verrouiller les notes" (= valider l'étudiant)
// #160 — Hiérarchie critère / sous-critères : un ItemEvaluation avec
//         sousCriteres non-vide s'affiche comme 1 ligne d'en-tête en lecture
//         seule (sous-total agrégé) + N lignes sous-critères indentées, qui
//         affichent chacune sa propre icône + score (comme un item normal).
//         Écran purement informatif : aucune saisie ici, donc pas de
//         GradingBloc.add() à ajouter — seul l'aplatissement change.

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../../core/utils/score_utils.dart';
import '../../domain/entities/item_evaluation.dart';
import '../../domain/entities/notation.dart';
import '../bloc/grading_bloc.dart';


// ── Couleurs adaptatives détail étudiant ─────────────────────────
class _DC {
  final bool d;
  const _DC(this.d);
  Color get bg        => d ? const Color(0xFF1A1F14) : AppTheme.background;
  Color get surface   => d ? const Color(0xFF252B1E) : AppTheme.surface;
  Color get border    => d ? const Color(0xFF3A4E28) : const Color(0xFFEEEBE3);
  Color get textPrim  => d ? Colors.white             : AppTheme.textPrimary;
  Color get textSec   => d ? const Color(0xFFAABB99)  : AppTheme.textSecondary;
  Color get lockBg    => d ? const Color(0xFF1E2A14)  : AppTheme.surface;
}

// ═══════════════════════════════════════════════════════════════════════════════
// MODÈLE DE LIGNES — même principe que grading_screen.dart (#160)
// ═══════════════════════════════════════════════════════════════════════════════
class _DetailRow {
  final ItemEvaluation displayItem;
  final bool           isHeader;      // ligne de regroupement, lecture seule agrégée
  final bool           isSousCritere; // ligne indentée sous un critère parent

  const _DetailRow({
    required this.displayItem,
    this.isHeader      = false,
    this.isSousCritere = false,
  });
}

List<_DetailRow> _buildDetailRows(List<ItemEvaluation> items) {
  final rows = <_DetailRow>[];
  for (final item in items) {
    if (item.hasSousCriteres) {
      rows.add(_DetailRow(displayItem: item, isHeader: true));
      for (final enfant in item.sousCriteres) {
        rows.add(_DetailRow(displayItem: enfant, isSousCritere: true));
      }
    } else {
      rows.add(_DetailRow(displayItem: item));
    }
  }
  return rows;
}

class StudentDetailScreen extends StatefulWidget {
  final dynamic   etudiant;    // Etudiant
  final String    stationNom;

  const StudentDetailScreen({
    super.key,
    required this.etudiant,
    required this.stationNom,
  });

  @override
  State<StudentDetailScreen> createState() => _StudentDetailScreenState();
}

class _StudentDetailScreenState extends State<StudentDetailScreen> {
  bool _absent = false;
  final _remarqueController = TextEditingController();

  // #335 — ROUVRIR NE MENT PLUS. Ces deux champs partaient de zéro à chaque
  // ouverture alors que l'entité porte la vérité serveur : un étudiant déclaré
  // ABSENT réapparaissait « Présent », sa remarque disparaissait — et
  // re-verrouiller depuis cet écran aurait écrasé la vérité par les valeurs
  // par défaut. Les notes, elles, étaient restaurées (notationExistante),
  // c'est ce qui rendait le piège invisible.
  @override
  void initState() {
    super.initState();
    _absent = widget.etudiant.absent;
    _remarqueController.text = widget.etudiant.commentaire ?? '';
  }

  @override
  void dispose() {
    _remarqueController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<GradingBloc, GradingState>(
      builder: (context, state) {
        if (state is! GradingLoaded) {
          return const Scaffold(
            body: Center(child: CircularProgressIndicator()),
          );
        }

        final etudiantId  = widget.etudiant.id as int;
        final items       = state.grille.items;
        final notations   = state.notations[etudiantId] ?? {};
        final estValide   = state.etudiantsValides.contains(etudiantId);
        final score       = state.scoreEtudiant(etudiantId);
        final noteMax     = state.grille.noteMax;

        return Scaffold(
          backgroundColor: _DC(Theme.of(context).brightness == Brightness.dark).bg,
          body: Column(
            children: [
              // ── Header ──────────────────────────────────
              _DetailHeader(
                etudiant:   widget.etudiant,
                stationNom: widget.stationNom,
                score:      score,
                noteMax:    noteMax,
                estValide:  estValide,
              ),

              // ── Contenu scrollable ───────────────────────
              Expanded(
                child: SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // ── Toggle Présent / Absent ──────────
                      _PresenceToggle(
                        absent:    _absent,
                        estValide: estValide,
                        onChanged: (val) => setState(() => _absent = val),
                      ),

                      const SizedBox(height: 20),

                      // ── Liste des critères ───────────────
                      _SectionLabel('Critères'),
                      const SizedBox(height: 8),
                      _CriteresList(
                        items:      items,
                        notations:  notations,
                        estValide:  estValide,
                        absent:     _absent,
                        etudiantId: etudiantId,
                      ),

                      const SizedBox(height: 20),

                      // ── Zone Remarque ────────────────────
                      _SectionLabel('Remarque'),
                      const SizedBox(height: 8),
                      _RemarqueField(
                        controller: _remarqueController,
                        estValide:  estValide,
                        absent:     _absent,
                      ),

                      const SizedBox(height: 24),
                    ],
                  ),
                ),
              ),

              // ── Bouton Verrouiller / Déverrouiller ───────
              _LockButton(
                estValide:  estValide,
                absent:     _absent,
                etudiantId: etudiantId,
                stationId:  state.stationId,
                remarqueController: _remarqueController,
              ),
            ],
          ),
        );
      },
    );
  }
}

// ════════════════════════════════════════════════
// HEADER
// ════════════════════════════════════════════════
class _DetailHeader extends StatelessWidget {
  final dynamic  etudiant;
  final String   stationNom;
  final double   score;
  final double   noteMax;
  final bool     estValide;

  const _DetailHeader({
    required this.etudiant,
    required this.stationNom,
    required this.score,
    required this.noteMax,
    required this.estValide,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      color: AppTheme.primaryDark,
      padding: EdgeInsets.only(
        top:    MediaQuery.of(context).padding.top + 8,
        left:   4,
        right:  16,
        bottom: 16,
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Ligne 1 : retour + nom + station
          Row(
            children: [
              IconButton(
                icon: const Icon(Icons.arrow_back_ios_new,
                    color: Colors.white, size: 18),
                onPressed: () => Navigator.of(context).pop(),
              ),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      etudiant.nomComplet,
                      style: const TextStyle(
                        color: Colors.white,
                        fontSize: 17,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    Text(
                      stationNom,
                      style: TextStyle(
                        color: Colors.white.withValues(alpha: 0.7),
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),

          // Score + numéro d'inscription
          Padding(
            padding: const EdgeInsets.only(left: 16, right: 4),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'N° ${etudiant.numeroInscription}',
                  style: TextStyle(
                    color: Colors.white.withValues(alpha: 0.7),
                    fontSize: 12,
                  ),
                ),
                Container(
                  padding: const EdgeInsets.symmetric(
                      horizontal: 14, vertical: 6),
                  decoration: BoxDecoration(
                    color: AppTheme.scoreColor(score)
                        .withValues(alpha: 0.25),
                    borderRadius: BorderRadius.circular(20),
                    border: Border.all(
                      color: AppTheme.scoreColor(score)
                          .withValues(alpha: 0.6),
                    ),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      if (estValide) ...[
                        const Icon(Icons.lock,
                            size: 12, color: Colors.white70),
                        const SizedBox(width: 4),
                      ],
                      Text(
                        'Score : ${score.toStringAsFixed(1)}/${noteMax.toInt()}',
                        style: const TextStyle(
                          color: Colors.white,
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ════════════════════════════════════════════════
// TOGGLE PRÉSENT / ABSENT
// ════════════════════════════════════════════════
class _PresenceToggle extends StatelessWidget {
  final bool     absent;
  final bool     estValide;
  final void Function(bool) onChanged;

  const _PresenceToggle({
    required this.absent,
    required this.estValide,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final dc = _DC(Theme.of(context).brightness == Brightness.dark);
    return Container(
      decoration: BoxDecoration(
        color: dc.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: dc.border),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      child: Row(
        children: [
          // Présent
          Expanded(
            child: _PresenceOption(
              label:    'Présent',
              selected: !absent,
              color:    AppTheme.scoreGreen,
              icon:     Icons.check_circle_outline,
              onTap:    estValide ? null : () => onChanged(false),
            ),
          ),
          const SizedBox(width: 12),
          // Absent
          Expanded(
            child: _PresenceOption(
              label:    'Absent',
              selected: absent,
              color:    AppTheme.scoreRed,
              icon:     Icons.cancel_outlined,
              onTap:    estValide ? null : () => onChanged(true),
            ),
          ),
        ],
      ),
    );
  }
}

class _PresenceOption extends StatelessWidget {
  final String    label;
  final bool      selected;
  final Color     color;
  final IconData  icon;
  final VoidCallback? onTap;

  const _PresenceOption({
    required this.label,
    required this.selected,
    required this.color,
    required this.icon,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 200),
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: selected
              ? color.withValues(alpha: 0.12)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(
            color: selected ? color : Colors.transparent,
          ),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon,
                color: selected ? color : AppTheme.criterionPending,
                size: 18),
            const SizedBox(width: 6),
            Text(
              label,
              style: TextStyle(
                color: selected
                    ? color
                    : _DC(Theme.of(context).brightness == Brightness.dark).textSec,
                fontWeight:
                selected ? FontWeight.w600 : FontWeight.w400,
                fontSize: 14,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ════════════════════════════════════════════════
// LISTE DES CRITÈRES (#160 — aplatit critère/sous-critères)
// ════════════════════════════════════════════════
class _CriteresList extends StatelessWidget {
  final List<ItemEvaluation>  items;
  final Map<int, Notation>    notations;
  final bool                  estValide;
  final bool                  absent;
  final int                   etudiantId;

  const _CriteresList({
    required this.items,
    required this.notations,
    required this.estValide,
    required this.absent,
    required this.etudiantId,
  });

  @override
  Widget build(BuildContext context) {
    final dc   = _DC(Theme.of(context).brightness == Brightness.dark);
    final rows = _buildDetailRows(items);

    return Container(
      decoration: BoxDecoration(
        color: dc.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: dc.border),
      ),
      child: Column(
        children: rows.asMap().entries.map((entry) {
          final i      = entry.key;
          final row    = entry.value;
          final isLast = i == rows.length - 1;

          return Column(
            children: [
              _CritereRow(
                row:        row,
                notation:   row.isHeader ? null : notations[row.displayItem.id],
                notations:  notations, // utile pour le sous-total agrégé d'une ligne d'en-tête
                estValide:  estValide,
                absent:     absent,
                etudiantId: etudiantId,
              ),
              if (!isLast)
                Divider(height: 1, color: dc.border),
            ],
          );
        }).toList(),
      ),
    );
  }
}

class _CritereRow extends StatelessWidget {
  final _DetailRow          row;
  final Notation?           notation;
  final Map<int, Notation>  notations;
  final bool                estValide;
  final bool                absent;
  final int                 etudiantId;

  const _CritereRow({
    required this.row,
    required this.notation,
    required this.notations,
    required this.estValide,
    required this.absent,
    required this.etudiantId,
  });

  @override
  Widget build(BuildContext context) {
    final item = row.displayItem;
    final dc   = _DC(Theme.of(context).brightness == Brightness.dark);

    // ── Ligne d'en-tête d'un critère à sous-critères : lecture seule,
    //    score agrégé via ScoreUtils.scoreCritere (somme des sous-critères
    //    notés), pas d'icône Fait/Non fait individuelle. ──────────────────
    if (row.isHeader) {
      final sousTotal = absent
          ? 0.0
          : ScoreUtils.scoreCritere(item: item, notations: notations);

      return Container(
        color: dc.surface.withValues(alpha: 0.6), // légère distinction visuelle
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
        child: Row(
          children: [
            // Liseré de regroupement — remplace l'icône dossier.
            Container(
              width: 3,
              height: 20,
              decoration: BoxDecoration(
                color: AppTheme.primary,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Text(
                '${item.libelle} :',
                style: TextStyle(
                  fontSize: 13,
                  fontWeight: FontWeight.w700,
                  color: dc.textPrim,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: AppTheme.primary.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                'x${ScoreUtils.fmtPoints(item.ponderation)}',
                style: const TextStyle(
                  fontSize: 11,
                  color: AppTheme.primary,
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            const SizedBox(width: 10),
            SizedBox(
              width: 44,
              child: Text(
                absent
                    ? '—'
                    : '${ScoreUtils.fmtPoints(sousTotal)}/${ScoreUtils.fmtPoints(item.ponderation)}',
                textAlign: TextAlign.right,
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  color: dc.textPrim,
                ),
              ),
            ),
          ],
        ),
      );
    }

    // ── Ligne d'un item simple ou d'un sous-critère : comportement
    //    identique à l'original, avec juste une indentation en plus pour
    //    matérialiser la hiérarchie sous son critère parent. ────────────
    double itemScore = 0;
    if (notation != null && !absent) {
      if (item.type == TypeCritere.binaire) {
        itemScore = notation!.valeur * item.ponderation;
      } else {
        itemScore = notation!.valeur.clamp(0.0, item.valeurMax);
      }
    }

    final bool? fait = notation == null
        ? null
        : (item.type == TypeCritere.binaire ? notation!.valeur == 1.0 : null);

    final Color iconColor = absent
        ? AppTheme.criterionPending
        : fait == null
        ? AppTheme.criterionPending
        : fait
        ? AppTheme.criterionDone
        : AppTheme.criterionNotDone;

    return Padding(
      padding: EdgeInsets.fromLTRB(row.isSousCritere ? 32 : 16, 12, 16, 12),
      child: Row(
        children: [
          // Icône état
          Icon(
            absent
                ? Icons.remove_circle_outline
                : fait == null
                ? Icons.radio_button_unchecked
                : fait
                ? Icons.check_circle
                : Icons.cancel,
            color: iconColor,
            size: row.isSousCritere ? 18 : 22,
          ),
          const SizedBox(width: 12),

          // Libellé + pondération
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  row.isSousCritere ? '•  ${item.libelle}' : item.libelle,
                  style: TextStyle(
                    fontSize: row.isSousCritere ? 12 : 13,
                    color: dc.textPrim,
                    height: 1.3,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 8),

          // Badge pondération
          Container(
            padding: const EdgeInsets.symmetric(
                horizontal: 8, vertical: 3),
            decoration: BoxDecoration(
              color: AppTheme.primary.withValues(alpha: 0.08),
              borderRadius: BorderRadius.circular(6),
            ),
            child: Text(
              'x${ScoreUtils.fmtPoints(item.ponderation)}',
              style: const TextStyle(
                fontSize: 11,
                color: AppTheme.primary,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
          const SizedBox(width: 10),

          // Score item
          SizedBox(
            width: 36,
            child: Text(
              absent
                  ? '—'
                  : notation == null
                  ? '—'
                  : item.type == TypeCritere.binaire
                  ? '${ScoreUtils.fmtPoints(itemScore)}/${ScoreUtils.fmtPoints(item.ponderation)}'
                  : '${itemScore.toStringAsFixed(1)}/${ScoreUtils.fmtPoints(item.valeurMax)}',
              textAlign: TextAlign.right,
              style: TextStyle(
                fontSize: 12,
                fontWeight: FontWeight.w700,
                color: absent
                    ? AppTheme.criterionPending
                    : notation == null
                    ? AppTheme.criterionPending
                    : dc.textPrim,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ════════════════════════════════════════════════
// ZONE REMARQUE
// ════════════════════════════════════════════════
class _RemarqueField extends StatelessWidget {
  final TextEditingController controller;
  final bool                  estValide;
  final bool                  absent;

  const _RemarqueField({
    required this.controller,
    required this.estValide,
    this.absent = false,
  });

  @override
  Widget build(BuildContext context) {
    final dc = _DC(Theme.of(context).brightness == Brightness.dark);
    return Container(
      decoration: BoxDecoration(
        color: dc.surface,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: dc.border),
      ),
      padding: const EdgeInsets.all(12),
      child: TextField(
        controller:  controller,
        enabled:     !estValide,
        maxLines:    4,
        style: TextStyle(
          fontSize: 13,
          color: dc.textPrim,
        ),
        decoration: InputDecoration(
          // Texte indicatif adapté au contexte
          hintText: absent
              ? 'Motif d\'absence (ex : certificat médical, retard)...'
              : 'Ajouter une remarque...',
          hintStyle:       TextStyle(
            color: AppTheme.criterionPending,
            fontSize: 13,
          ),
          border:          InputBorder.none,
          contentPadding:  EdgeInsets.zero,
          isDense:         true,
        ),
      ),
    );
  }
}

// ════════════════════════════════════════════════
// BOUTON VERROUILLER / DÉVERROUILLÉ
// ════════════════════════════════════════════════
class _LockButton extends StatelessWidget {
  final bool estValide;
  final bool absent;
  final int  etudiantId;
  final int  stationId;
  final TextEditingController remarqueController;

  const _LockButton({
    required this.estValide,
    required this.absent,
    required this.etudiantId,
    required this.stationId,
    required this.remarqueController,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: _DC(Theme.of(context).brightness == Brightness.dark).lockBg,
      padding: EdgeInsets.only(
        left:   16,
        right:  16,
        top:    12,
        bottom: MediaQuery.of(context).padding.bottom + 12,
      ),
      child: ElevatedButton.icon(
        onPressed: () => estValide
            ? _afficherMessageVerrouille(context)
            : _confirmerVerrouillage(context),
        icon: Icon(
          estValide ? Icons.lock : Icons.lock_open_outlined,
          size: 18,
        ),
        label: Text(
          estValide ? 'Notes verrouillées' : 'Verrouiller les notes',
        ),
        style: ElevatedButton.styleFrom(
          backgroundColor:
          estValide ? AppTheme.criterionPending : AppTheme.primaryDark,
          foregroundColor: Colors.white,
          padding: const EdgeInsets.symmetric(vertical: 14),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
          textStyle: const TextStyle(
            fontSize: 15,
            fontWeight: FontWeight.w600,
          ),
        ),
      ),
    );
  }

  void _confirmerVerrouillage(BuildContext context) {
    showDialog(
      context: context,
      builder: (_) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(absent ? 'Marquer absent ?' : 'Verrouiller les notes ?'),
        content: Text(
          absent
              ? 'L\'étudiant sera marqué absent avec un score de 0/20.'
              : 'Une fois verrouillées, les notes ne pourront plus être '
              'modifiées sans déverrouillage par le responsable.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Annuler'),
          ),
          ElevatedButton(
            onPressed: () {
              Navigator.pop(context);

              final remarque = remarqueController.text.trim();
              context.read<GradingBloc>().add(
                GradingEtudiantValide(
                  etudiantId,
                  absent:      absent,
                  commentaire: remarque.isNotEmpty ? remarque : null,
                ),
              );

              Navigator.of(context).pop();
            },
            style: ElevatedButton.styleFrom(
              backgroundColor:
              absent ? AppTheme.scoreRed : AppTheme.primaryDark,
            ),
            child: Text(
              absent ? 'Confirmer l\'absence' : 'Verrouiller',
              style: const TextStyle(color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }


  void _afficherMessageVerrouille(BuildContext context) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: const Text(
          'Notes verrouillées. Contactez le responsable pour déverrouiller.',
        ),
        backgroundColor: AppTheme.criterionPending,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(10)),
        margin: const EdgeInsets.all(16),
      ),
    );
  }
}

// ════════════════════════════════════════════════
// UTILITAIRES
// ════════════════════════════════════════════════
class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel(this.text);

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w700,
        color: _DC(Theme.of(context).brightness == Brightness.dark).textPrim,
      ),
    );
  }
}