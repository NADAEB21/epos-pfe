// lib/features/grading/presentation/screens/grading_screen.dart

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
//import 'dart:ui';

import '../../../../core/theme/app_theme.dart';
import '../../domain/entities/item_evaluation.dart';
import '../../domain/entities/notation.dart';
import '../../../home/domain/entities/session.dart';
import '../bloc/grading_bloc.dart';
import 'student_detail_screen.dart';

// ── Dimensions adaptatives ────────────────────────────────────────
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

  /// Calcule le layout selon la largeur disponible et le nb d'étudiants.
  /// Sur tablette : on essaie d'afficher tous les étudiants sans scroll.
  /// Sur mobile  : colonnes fixes, scroll horizontal activé.
  factory _Layout.of(BuildContext context, int nbEtudiants) {
    final width    = MediaQuery.of(context).size.width;
    final isTablet = width >= 600;

    if (isTablet) {
      // Sur tablette : calcule la largeur idéale pour tout afficher
      const double critereWidth = 160.0;
      const double minStudentW  =  90.0;
      const double divider      =   1.0;

      // Largeur disponible pour les colonnes étudiants
      final available = width - critereWidth - divider;
      // Largeur par étudiant : idéalement équilibrée, minimum 90
      final studentW  = (available / nbEtudiants).clamp(minStudentW, 160.0);

      return _Layout(
        critereColWidth: critereWidth,
        studentColWidth: studentW,
        headerHeight:    120.0,
        cellMinHeight:    80.0,
        isTablet:        true,
      );
    } else {
      // Mobile : valeurs fixes calibrées pour ~360–430px
      return const _Layout(
        critereColWidth: 125.0,
        studentColWidth:  95.0,
        headerHeight:    110.0,
        cellMinHeight:    75.0,
        isTablet:        false,
      );
    }
  }

  /// Largeur totale de la zone étudiants
  double get studentAreaWidth => studentColWidth * 1; // multiplié par nbEtudiants à l'usage
  
  /// Vrai si le scroll horizontal est nécessaire
  bool needsHScroll(BuildContext context, int nbEtudiants) {
    final totalContent = critereColWidth + 1 + studentColWidth * nbEtudiants;
    return totalContent > MediaQuery.of(context).size.width;
  }
}

// ── Couleurs adaptatives ──────────────────────────────────────────
class _GC {
  final bool d;
  const _GC(this.d);
  Color get bg => d ? const Color(0xFF1A1F14) : AppTheme.background;
  Color get rowEven => d ? const Color(0xFF1E2518) : AppTheme.background;
  Color get rowOdd => d ? const Color(0xFF222D19) : const Color(0xFFF7F5EF);
  Color get rowHeader => d ? const Color(0xFF2A3320) : AppTheme.surface;
  Color get border => d ? const Color(0xFF3A4E28) : const Color(0xFFEEEBE3);
  Color get textPrim => d ? Colors.white : AppTheme.textPrimary;
  Color get textSec => d ? const Color(0xFFAABB99) : AppTheme.textSecondary;
  Color get numFill => d ? const Color(0xFF2C3822) : AppTheme.surface;
  Color get numBorder => d ? const Color(0xFF4A6030) : const Color(0xFFDDD8CC);
}

class GradingScreen extends StatelessWidget {
  final Session session;
  const GradingScreen({super.key, required this.session});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _GC(Theme.of(context).brightness == Brightness.dark).bg,
      body: BlocConsumer<GradingBloc, GradingState>(
        listener: (context, state) {
          if (state is GradingError) {
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(
              content: Text(state.message),
              backgroundColor: AppTheme.scoreRed,
              behavior: SnackBarBehavior.floating,
            ));
          }
        },
        builder: (context, state) {
          if (state is GradingLoaded) return _GradingView(state: state);
          return const Center(child: CircularProgressIndicator(color: AppTheme.primary));
        },
      ),
    );
  }
}

class _GradingView extends StatefulWidget {
  final GradingLoaded state;
  const _GradingView({required this.state});

  @override
  State<_GradingView> createState() => _GradingViewState();
}

class _GradingViewState extends State<_GradingView> {
  // 3 controllers séparés — chacun attaché à UN SEUL ScrollView
  late final ScrollController _headerHScroll; // en-tête avatars
  late final ScrollController _bodyHScroll;   // grille corps
  late final ScrollController _footerHScroll; // footer scores

  bool _syncing = false; // garde-fou anti-boucle infinie

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

    // Quand l'un bouge, les autres suivent
    _headerHScroll.addListener(() =>
        _syncFrom(_headerHScroll, [_bodyHScroll, _footerHScroll]));
    _bodyHScroll.addListener(() =>
        _syncFrom(_bodyHScroll, [_headerHScroll, _footerHScroll]));
    // Footer NeverScrollable → pas besoin de listener sur lui
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

  return Column(
    children: [
      _GradingAppBar(state: widget.state),
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
  );
}
}

// ════════════════════════════════════════════════════════════════
// CORPS — Stack avec colonne Critère fixe overlay
// L'en-tête avatars a été retiré d'ici (il est maintenant
// dans _StickyStudentHeader au-dessus)
// ════════════════════════════════════════════════════════════════
class _GradingBody extends StatelessWidget {
  final GradingLoaded    state;
  final ScrollController hScroll;
  final _Layout          layout;
  const _GradingBody({required this.state, required this.hScroll, required this.layout});

  @override
  Widget build(BuildContext context) {
    final gc        = _GC(Theme.of(context).brightness == Brightness.dark);
    final items     = state.grille.items;
    final etudiants = state.lot.etudiants;

    return SingleChildScrollView(
      scrollDirection: Axis.vertical,
      child: Stack(
        children: [

          // ── 1. Grille scrollable horizontalement ──
          SingleChildScrollView(
            controller:      hScroll,
            scrollDirection: Axis.horizontal,
            physics:         const ClampingScrollPhysics(),
            child: IntrinsicWidth(
              child: Column(
                children: items.asMap().entries.map((entry) {
                  return IntrinsicHeight(
                    child: Row(
                      children: [
                        // Cellule Critère INVISIBLE — sert uniquement
                        // à forcer la hauteur de la ligne (le vrai texte
                        // est affiché par l'overlay Positioned ci-dessous)
                        _CritereCell(
                          item:      entry.value,
                          gc:        gc,
                          invisible: true,
                          width:     layout.critereColWidth,     // ← adaptatif
                          minHeight: layout.cellMinHeight,       // ← adaptatif
                        ),
                        // Cellules de notation
                        ...etudiants.map((e) => Container(
                          width: layout.studentColWidth,
                          decoration: BoxDecoration(
                            color: entry.key.isEven ? gc.rowEven : gc.rowOdd,
                            border: Border(
                              left:   BorderSide(color: gc.border, width: 0.5),
                              bottom: BorderSide(color: gc.border, width: 0.8),
                            ),
                          ),
                          child: _NotationCell(
                            etudiant:  e,
                            item:      entry.value,
                            notation:  state.notations[e.id]?[entry.value.id],
                            estValide: state.etudiantsValides.contains(e.id),
                          ),
                        )),
                      ],
                    ),
                  );
                }).toList(),
              ),
            ),
          ),

          // ── 2. Overlay Critère FIXE (ne scroll pas horizontalement) ──
          Positioned(
            left: 0, top: 0, bottom: 0,
            child: Container(
              width: layout.critereColWidth,
              decoration: BoxDecoration(
                color: gc.bg,
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withOpacity(0.10),
                    blurRadius: 4,
                    offset: const Offset(2, 0),
                  ),
                ],
              ),
              child: Column(
                children: items.asMap().entries.map((entry) {
                  return _CritereCell(
                    item:  entry.value,
                    gc:    gc,
                    index: entry.key,
                    width: layout.critereColWidth,     // ← adaptatif
                    minHeight: layout.cellMinHeight,   // ← adaptatif
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
  final ItemEvaluation item;
  final _GC gc;
  final int? index;
  final bool invisible;
  final double         width;      // ← plus de constante
  final double         minHeight;  // ← plus de constante

  const _CritereCell({required this.item, required this.gc, this.index, this.invisible = false, required this.width, required this.minHeight,});

  @override
  Widget build(BuildContext context) {
    final bg = invisible ? Colors.transparent : (index ! % 2 == 0 ? gc.rowEven : gc.rowOdd);
    return Container(
      width: width,
      constraints: BoxConstraints(minHeight: minHeight), 
      padding: const EdgeInsets.all(10),
      decoration: BoxDecoration(
        color: bg,
        border: Border(bottom: BorderSide(color: gc.border, width: 0.5)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisAlignment: MainAxisAlignment.center,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(item.libelle, 
            style: TextStyle(fontSize: 11, color: invisible ? Colors.transparent : gc.textPrim, height: 1.3)),
          const SizedBox(height: 4),
          Text('${item.ponderation.toInt()} pts', 
            style: TextStyle(fontSize: 10, color: invisible ? Colors.transparent : AppTheme.primary, fontWeight: FontWeight.bold)),
        ],
      ),
    );
  }
}

// ── Footer ──────────────────────────────────────────────────────
class _GradingFooter extends StatelessWidget {
  final GradingLoaded state;
  final ScrollController hScroll;
  final _Layout          layout; 

  const _GradingFooter({required this.state, required this.hScroll, required this.layout,});

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: AppTheme.primaryDark,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      padding: EdgeInsets.fromLTRB(0, 14, 0, MediaQuery.of(context).padding.bottom + 14),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              SizedBox(
                width: layout.critereColWidth, // aligné avec la colonne Critère
                child: Padding(
                  padding: EdgeInsets.only(left: 16),
                  child: Text('Score /20', style: TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.bold)),
                ),
              ),
              Expanded(
                child: SingleChildScrollView(
                  controller: hScroll,
                  scrollDirection: Axis.horizontal,
                  physics: const NeverScrollableScrollPhysics(),
                  child: Row(
                    children: state.lot.etudiants.map((e) {
                      final s = state.scoreEtudiant(e.id);
                      return SizedBox(
                        width: layout.studentColWidth,
                        child: Center(
                          child: Container(
                            width: 65,
                            padding: const EdgeInsets.symmetric(vertical: 6),
                            decoration: BoxDecoration(
                              color: AppTheme.scoreColor(s),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Text(
                              s.toStringAsFixed(s == s.truncateToDouble() ? 0 : 1),
                              textAlign: TextAlign.center,
                              style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 15),
                            ),
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
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: state.lotEnCoursDeValidation ? null : () => _confirmerValidation(context),
                    icon: const Icon(Icons.check, size: 18),
                    label: const Text('Valider lot'),
                    style: OutlinedButton.styleFrom(
                      foregroundColor: Colors.white,
                      side: const BorderSide(color: Colors.white54),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton.icon(
                    onPressed: state.lot.numero >= state.lot.total ? null : () => _confirmerLotSuivant(context),
                    icon: const Icon(Icons.arrow_forward, size: 18),
                    label: const Text('Lot suivant'),
                    style: ElevatedButton.styleFrom(
                      backgroundColor: AppTheme.accent,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
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
    final nonValides = state.lot.etudiants.where((e) => !state.etudiantsValides.contains(e.id)).toList();
    if (nonValides.isNotEmpty) {
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('Étudiants non validés'),
          content: Text('${nonValides.length} étudiant(s) restants. Valider quand même ?'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
            ElevatedButton(onPressed: () { Navigator.pop(context); context.read<GradingBloc>().add(const GradingLotValide()); }, child: const Text('Confirmer')),
          ],
        ),
      );
    } else {
      context.read<GradingBloc>().add(const GradingLotValide());
    }
  }

  void _confirmerLotSuivant(BuildContext context) {
    if (!state.tousLesEtudiantsValides) {
      showDialog(
        context: context,
        builder: (_) => AlertDialog(
          title: const Text('Lot suivant ?'),
          content: const Text('Certains étudiants ne sont pas validés. Voulez-vous continuer ?'),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
            ElevatedButton(onPressed: () { Navigator.pop(context); context.read<GradingBloc>().add(const GradingLotSuivantDemande()); }, child: const Text('Continuer')),
          ],
        ),
      );
    } else {
      context.read<GradingBloc>().add(const GradingLotSuivantDemande());
    }
  }
}

// ── Éléments de cellule ──────────────────────────────────────────
// ── Cellule de notation ──────────────────────────────────────────
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
      final bool? fait = notation == null ? null : notation!.valeur == 1.0;
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
            width: 38, height: 38,
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
                    size: 20,
                    color: fait ? Colors.green : Colors.red,
                  ),
          ),
        ),
      );
    }

    // Cellule numérique — StatefulWidget dédié
    return _NumericCell(
      etudiant:  etudiant,
      item:      item,
      notation:  notation,
      estValide: estValide,
    );
  }
}

// ── Cellule numérique — controller persistant ────────────────────
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

  // Formate sans arrondi :
  //   3.0   → "3"
  //   3.5   → "3.5"
  //   3.50  → "3.5"  (pas de zéro inutile)
  String _fmt(double v) {
    if (v == v.truncateToDouble()) return v.toInt().toString();
    // Retire les zéros trailing sans arrondir
    return v.toString().replaceAll(RegExp(r'0+$'), '').replaceAll(RegExp(r'\.$'), '');
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
          controller:   _ctrl,
          enabled:      !widget.estValide,
          textAlign:    TextAlign.center,
          keyboardType: const TextInputType.numberWithOptions(decimal: true),
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
            isDense:   true,
            filled:    true,
            fillColor: gc.numFill,
            hintText:  '—',
            hintStyle: TextStyle(color: gc.textSec, fontSize: 14),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: BorderSide(color: gc.numBorder),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: BorderSide(color: gc.numBorder),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(8),
              borderSide: const BorderSide(color: AppTheme.primary, width: 2),
            ),
          ),
          onChanged: (val) {
            // Champ vide → valeur 0 dans le bloc mais on garde l'affichage vide
            if (val.trim().isEmpty || val == '.') {
              context.read<GradingBloc>().add(GradingNumericUpdated(
                etudiantId: widget.etudiant.id,
                itemId:     widget.item.id,
                valeur:     0,
              ));
              return;
            }
            // Le formatter a déjà normalisé la virgule → point
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

// ── Formatter : virgule → point, chiffres uniquement, max respecté ─
class _DecimalFormatter extends TextInputFormatter {
  final double max;
  _DecimalFormatter({required this.max});

  @override
  TextEditingValue formatEditUpdate(
    TextEditingValue oldValue,
    TextEditingValue newValue,
  ) {
    // Normalise les séparateurs décimaux (virgule arabe incluse)
    final normalized = newValue.text
        .replaceAll(',', '.')
        .replaceAll('\u060C', '.')
        .replaceAll('\u066B', '.');

    // N'autorise que chiffres + un seul point
    if (!RegExp(r'^\d*\.?\d*$').hasMatch(normalized)) return oldValue;

    // Bloque si la valeur dépasse le max
    // (on vérifie seulement si c'est un nombre complet, pas "3.")
    final parsed = double.tryParse(normalized);
    if (parsed != null && parsed > max) return oldValue;

    // Retourne la valeur normalisée avec la sélection à la fin
    return newValue.copyWith(
      text:      normalized,
      selection: TextSelection.collapsed(offset: normalized.length),
    );
  }
}

// ════════════════════════════════════════════════════════════════
// EN-TÊTE AVATARS FIXE
// Scroll horizontal via hScroll (même controller que la grille)
// NeverScrollableScrollPhysics : le geste vient de la grille
// ════════════════════════════════════════════════════════════════
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
        // Légère ombre vers le bas pour bien séparer l'en-tête du corps
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(
                Theme.of(context).brightness == Brightness.dark ? 0.3 : 0.08),
            blurRadius: 4,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      height: layout.headerHeight,      // ← adaptatif
      child: Row(
        children: [
          // Cellule "Critère" alignée avec la colonne fixe de la grille
          Container(
            width: layout.critereColWidth, // ← adaptatif
            padding: const EdgeInsets.symmetric(horizontal: 12),
            alignment: Alignment.centerLeft,
            color: gc.rowHeader,
            child: Text(
              'Critère',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color:      gc.textSec,
                fontSize:   13,
              ),
            ),
          ),

          // Zone avatars — suit le scroll horizontal de la grille
          Expanded(
            child: SingleChildScrollView(
              controller:      hScroll,
              scrollDirection: Axis.horizontal,
              // NeverScrollable ici : les gestes horizontaux
              // sont capturés par la grille en dessous.
              // Les deux ScrollViews partagent le même controller
              // donc ils bougent ensemble.
              physics: const ClampingScrollPhysics(),
              child: Row(
                children: etudiants
                    .map((e) => SizedBox(
                          width:  layout.studentColWidth,
                          height: layout.headerHeight,
                          child: _EtudiantHeader(
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

// ── Header Étudiant (Avatar + Navigation) ──────────────────────────
class _EtudiantHeader extends StatelessWidget {
  final dynamic etudiant;
  final bool estValide;
  final GradingLoaded state;

  const _EtudiantHeader({required this.etudiant, required this.estValide, required this.state});

  @override
  Widget build(BuildContext context) {
    final gc = _GC(Theme.of(context).brightness == Brightness.dark);
    return GestureDetector(
      onTap: () => Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => BlocProvider.value(
          value: context.read<GradingBloc>(),
          child: StudentDetailScreen(etudiant: etudiant, stationNom: state.stationNom),
        ),
      )),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          CircleAvatar(
            radius: 20,
            backgroundColor: estValide ? AppTheme.scoreGreen : AppTheme.primaryDark,
            child: Text(etudiant.initiales, style: const TextStyle(color: Colors.white, fontSize: 13, fontWeight: FontWeight.bold)),
          ),
          const SizedBox(height: 6),
          Text(etudiant.prenom, style: TextStyle(fontSize: 10, fontWeight: FontWeight.w600, color: gc.textPrim), overflow: TextOverflow.ellipsis),
          Text(etudiant.nom, style: TextStyle(fontSize: 9, color: gc.textSec), overflow: TextOverflow.ellipsis),
        ],
      ),
    );
  }
}

// ── AppBar & Timer ──────────────────────────────────────────────
class _GradingAppBar extends StatelessWidget {
  final GradingLoaded state;
  const _GradingAppBar({required this.state});

  @override
  Widget build(BuildContext context) {
    return Container(
      color: AppTheme.primaryDark,
      padding: EdgeInsets.only(top: MediaQuery.of(context).padding.top + 8, bottom: 12),
      child: Column(children: [
        Row(children: [
          IconButton(icon: const Icon(Icons.arrow_back_ios_new, color: Colors.white, size: 18), onPressed: () => Navigator.of(context, rootNavigator: true).pop()),
          Expanded(child: Text(state.stationNom, style: const TextStyle(color: Colors.white, fontSize: 17, fontWeight: FontWeight.bold))),
        ]),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(children: [
            _badge(state.lot.label, bold: true),
            const SizedBox(width: 8),
            _badge('${state.lot.etudiants.length} étudiants'),
            const Spacer(),
            _TimerBadge(restant: state.tempsRestant),
          ]),
        ),
      ]),
    );
  }

  Widget _badge(String t, {bool bold = false}) => Container(
    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
    decoration: BoxDecoration(color: Colors.white12, borderRadius: BorderRadius.circular(20)),
    child: Text(t, style: TextStyle(color: Colors.white, fontSize: 12, fontWeight: bold ? FontWeight.bold : FontWeight.normal)),
  );
}

class _TimerBadge extends StatelessWidget {
  final Duration? restant;
  const _TimerBadge({this.restant});

  @override
  Widget build(BuildContext context) {
    if (restant == null) return const SizedBox.shrink();
    final isDepasse = restant!.inSeconds <= 0;
    final aff = restant!.abs();
    final mm = aff.inMinutes.remainder(60).toString().padLeft(2, '0');
    final ss = aff.inSeconds.remainder(60).toString().padLeft(2, '0');
    final color = isDepasse ? AppTheme.scoreRed : Colors.white;
    
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: color.withOpacity(0.2), borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withOpacity(0.5)),
      ),
      child: Row(children: [
        Icon(isDepasse ? Icons.timer_off : Icons.timer, size: 14, color: color),
        const SizedBox(width: 5),
        Text('${isDepasse ? "+" : ""}$mm:$ss', style: TextStyle(color: color, fontSize: 13, fontWeight: FontWeight.bold, fontFeatures: const [FontFeature.tabularFigures()])),
      ]),
    );
  }
}