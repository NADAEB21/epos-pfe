// lib/core/offline/connectivity_banner.dart
// ================================================
// BF6.2 — Indicateur visuel d'état réseau et de synchronisation.
//
// Affichage :
//   🔴 Bannière rouge persistante  → hors-ligne
//   🟡 Bannière orange animée      → synchronisation en cours
//   🟢 Toast vert éphémère         → sync terminée avec succès
//   🟠 Toast orange éphémère       → sync partielle (certaines notations en échec)
//
// Design : fidèle à la palette EPOS (AppTheme). Animé via AnimatedSlide
// pour glisser depuis le haut sans perturber le layout de la grille.
//
// Usage : Envelopper le body de GradingScreen dans ConnectivityBanner.

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../theme/app_theme.dart';
import 'offline_bloc.dart';
import 'offline_storage_service.dart';

class ConnectivityBanner extends StatefulWidget {
  final Widget child;

  const ConnectivityBanner({super.key, required this.child});

  @override
  State<ConnectivityBanner> createState() => _ConnectivityBannerState();
}

class _ConnectivityBannerState extends State<ConnectivityBanner>
    with SingleTickerProviderStateMixin {
  late final AnimationController _slideController;
  late final Animation<Offset>   _slideAnim;

  @override
  void initState() {
    super.initState();
    _slideController = AnimationController(
      vsync:    this,
      duration: const Duration(milliseconds: 280),
    );
    _slideAnim = Tween<Offset>(
      begin: const Offset(0, -1),
      end:   Offset.zero,
    ).animate(CurvedAnimation(
      parent: _slideController,
      curve:  Curves.easeOutCubic,
    ));
  }

  @override
  void dispose() {
    _slideController.dispose();
    super.dispose();
  }

  void _showBanner()  => _slideController.forward();
  void _hideBanner()  => _slideController.reverse();

  @override
  Widget build(BuildContext context) {
    return BlocConsumer<OfflineBloc, OfflineState>(
      listenWhen: (prev, curr) =>
          prev.isOnline    != curr.isOnline    ||
          prev.isSyncing   != curr.isSyncing   ||
          prev.syncStatus  != curr.syncStatus,
      listener: (context, state) {
        // Bannière persistante : hors-ligne, sync en cours, ou #307 des notes
        // bloquées qui attendent un geste de l'évaluateur.
        if (!state.isOnline || state.isSyncing || state.aDesNotesBloquees) {
          _showBanner();
        } else {
          _hideBanner();
        }

        // Toast de résultat de synchronisation
        switch (state.syncStatus) {
          case SyncStatus.success:
            if (state.lastSyncedCount > 0) _showSyncToast(context, state);
            break;
          case SyncStatus.authExpired:
            _showAuthExpiredToast(context);
            break;
          case SyncStatus.blocked:
            // La bannière rouge persistante porte déjà le message : un toast
            // éphémère en plus ferait disparaître l'information au bout de 5 s.
            break;
          case SyncStatus.partialFailure:
            _showPartialFailureToast(context, state);
            break;
          default:
            break;
        }
      },
      buildWhen: (prev, curr) =>
          prev.isOnline     != curr.isOnline  ||
          prev.isSyncing    != curr.isSyncing ||
          prev.pendingCount != curr.pendingCount ||
          prev.blockedCount != curr.blockedCount,
      builder: (context, state) {
        return Stack(
          children: [
            // Contenu principal
            widget.child,

            // Bannière glissante
            SlideTransition(
              position: _slideAnim,
              child: _BannerContent(state: state),
            ),
          ],
        );
      },
    );
  }

  void _showSyncToast(BuildContext context, OfflineState state) {
    final n = state.lastSyncedCount;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Row(
          children: [
            const Icon(Icons.cloud_done_outlined, color: Colors.white, size: 18),
            const SizedBox(width: 10),
            // Le vert énonce l'état ATTEINT, pas l'opération technique :
            // « enregistrée sur le serveur » se comprend sans savoir ce
            // qu'est une synchronisation.
            Text(
              n == 1
                  ? '1 note enregistrée sur le serveur'
                  : '$n notes enregistrées sur le serveur',
              style: const TextStyle(fontFamily: 'Poppins', fontSize: 13),
            ),
          ],
        ),
        backgroundColor: AppTheme.scoreGreen,
        behavior:        SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        margin:          const EdgeInsets.all(16),
        duration:        const Duration(seconds: 3),
      ));
  }

  /// #307 — la session ne vaut plus : les notes sont intactes, il faut se
  /// reconnecter. Le message le dit dans cet ordre — d'abord ce qui rassure,
  /// ensuite ce qu'il faut faire.
  void _showAuthExpiredToast(BuildContext context) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: const Row(
          children: [
            Icon(Icons.lock_clock_outlined, color: Colors.white, size: 18),
            SizedBox(width: 10),
            Expanded(
              child: Text(
                'Vos notes sont conservées. Votre session a expiré : '
                'reconnectez-vous pour les envoyer.',
                style: TextStyle(fontFamily: 'Poppins', fontSize: 12),
              ),
            ),
          ],
        ),
        backgroundColor: const Color(0xFFB71C1C),
        behavior:        SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        margin:          const EdgeInsets.all(16),
        duration:        const Duration(seconds: 6),
      ));
  }

  void _showPartialFailureToast(BuildContext context, OfflineState state) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Row(
          children: [
            const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 18),
            const SizedBox(width: 10),
            Expanded(
              // Aucune action demandée : c'est un incident passager, l'app
              // réessaiera seule. Le message doit donc RASSURER, pas alerter.
              child: Text(
                '${state.lastSyncedCount} note(s) envoyée(s). '
                '${state.pendingCount} en attente — nouvel essai automatique.',
                style: const TextStyle(fontFamily: 'Poppins', fontSize: 12),
              ),
            ),
          ],
        ),
        backgroundColor: AppTheme.scoreOrange,
        behavior:        SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        margin:          const EdgeInsets.all(16),
        duration:        const Duration(seconds: 5),
        action: SnackBarAction(
          label:     'Réessayer',
          textColor: Colors.white,
          onPressed: () => context.read<OfflineBloc>().add(const OfflineSyncRequested()),
        ),
      ));
  }
}

// ── Contenu de la bannière ────────────────────────────────────────────────────

class _BannerContent extends StatelessWidget {
  final OfflineState state;
  const _BannerContent({required this.state});

  @override
  Widget build(BuildContext context) {
    if (state.isSyncing) {
      return _SyncingBanner(pendingCount: state.pendingCount);
    }
    // #307 — priorité au blocage : c'est le seul état qui demande un geste.
    // Il reste affiché même hors ligne, sinon l'information disparaît
    // précisément quand l'évaluateur en a le plus besoin.
    if (state.aDesNotesBloquees) {
      return _BlockedBanner(
        blockedCount: state.blockedCount,
        isOnline:     state.isOnline,
      );
    }
    if (!state.isOnline) {
      return _OfflineBanner(pendingCount: state.pendingCount);
    }
    return const SizedBox.shrink();
  }
}

/// #307 — bandeau rouge persistant : des notes ne sont pas parties.
///
/// Trois choses à dire, dans cet ordre, parce que c'est l'ordre des questions
/// que se pose l'enseignant :
///   1. combien de notes sont concernées,
///   2. qu'elles ne sont PAS perdues,
///   3. ce qu'il peut faire maintenant.
class _BlockedBanner extends StatelessWidget {
  final int  blockedCount;
  final bool isOnline;

  const _BlockedBanner({required this.blockedCount, required this.isOnline});

  @override
  Widget build(BuildContext context) {
    final n = blockedCount;
    return Material(
      color: Colors.transparent,
      child: Container(
        width:   double.infinity,
        color:   const Color(0xFFB71C1C),
        padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 16),
        child: SafeArea(
          bottom: false,
          child: Row(
            children: [
              const Icon(Icons.error_outline_rounded,
                  color: Colors.white, size: 18),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      n == 1
                          ? '1 note n\'est pas partie'
                          : '$n notes ne sont pas parties',
                      style: const TextStyle(
                        color:      Colors.white,
                        fontSize:   13,
                        fontFamily: 'Poppins',
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      isOnline
                          ? 'Elles sont conservées ici. Appuyez pour voir et réessayer.'
                          : 'Elles sont conservées ici. Réessayez une fois le réseau revenu.',
                      style: const TextStyle(
                        color:      Colors.white,
                        fontSize:   11,
                        fontFamily: 'Poppins',
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 8),
              TextButton(
                onPressed: () => afficherNotesBloquees(context),
                style: TextButton.styleFrom(
                  backgroundColor: Colors.white,
                  foregroundColor: const Color(0xFFB71C1C),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
                child: const Text(
                  'Voir',
                  style: TextStyle(
                    fontSize:   12,
                    fontFamily: 'Poppins',
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _OfflineBanner extends StatelessWidget {
  final int pendingCount;
  const _OfflineBanner({required this.pendingCount});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Container(
        width:   double.infinity,
        color:   const Color(0xFFB71C1C), // rouge foncé, plus visible que scoreRed
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
        child: SafeArea(
          bottom: false,
          child: Row(
            children: [
              const Icon(Icons.wifi_off_rounded, color: Colors.white, size: 16),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  pendingCount > 0
                      ? 'Hors-ligne — $pendingCount note(s) sauvegardée(s) localement'
                      : 'Hors-ligne — les notes sont sauvegardées localement',
                  style: const TextStyle(
                    color:      Colors.white,
                    fontSize:   12,
                    fontFamily: 'Poppins',
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              if (pendingCount > 0)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                  decoration: BoxDecoration(
                    color:        Colors.white.withOpacity(0.2),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: Text(
                    '$pendingCount',
                    style: const TextStyle(
                      color:      Colors.white,
                      fontSize:   12,
                      fontWeight: FontWeight.w700,
                      fontFamily: 'Poppins',
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SyncingBanner extends StatelessWidget {
  final int pendingCount;
  const _SyncingBanner({required this.pendingCount});

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: Container(
        width:   double.infinity,
        color:   AppTheme.scoreOrange,
        padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 16),
        child: SafeArea(
          bottom: false,
          child: Row(
            children: [
              const SizedBox(
                width:  14,
                height: 14,
                child:  CircularProgressIndicator(
                  strokeWidth: 2,
                  valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                ),
              ),
              const SizedBox(width: 10),
              Text(
                'Synchronisation en cours ($pendingCount note(s))…',
                style: const TextStyle(
                  color:      Colors.white,
                  fontSize:   12,
                  fontFamily: 'Poppins',
                  fontWeight: FontWeight.w500,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ── #307 — la liste des notes non parties ────────────────────────────────────

/// Ouvre la feuille « notes non parties ».
///
/// Elle nomme les ÉTUDIANTS, pas des identifiants : un enseignant doit pouvoir
/// dire « ah oui, Sonia, station 2 » et décider. Les libellés sont mémorisés
/// localement au chargement du lot, donc la liste reste lisible HORS LIGNE.
Future<void> afficherNotesBloquees(BuildContext context) async {
  final bloquees = await OfflineStorageService.instance.getBlockedNotations();
  final etudiants = await OfflineStorageService.instance
      .getLabels(OfflineStorageService.kindEtudiant);
  final stations = await OfflineStorageService.instance
      .getLabels(OfflineStorageService.kindStation);

  if (!context.mounted) return;

  // Regroupe par étudiant × station : l'enseignant raisonne par personne, pas
  // par critère. « 4 notes de Sonia Karoui » est lisible ; quatre lignes
  // « critère 37 » ne le sont pas.
  final groupes = <String, int>{};
  for (final n in bloquees) {
    final etu = etudiants[n.etudiantId] ?? 'Étudiant n°${n.etudiantId}';
    final sta = stations[n.stationId]   ?? 'Station n°${n.stationId}';
    groupes['$etu — $sta'] = (groupes['$etu — $sta'] ?? 0) + 1;
  }

  final motifs = bloquees
      .map((n) => n.lastError)
      .whereType<String>()
      .toSet()
      .toList();

  await showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
    ),
    builder: (sheetContext) => SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              bloquees.length == 1
                  ? '1 note n\'est pas partie'
                  : '${bloquees.length} notes ne sont pas parties',
              style: const TextStyle(
                fontSize:   17,
                fontFamily: 'Poppins',
                fontWeight: FontWeight.w700,
              ),
            ),
            const SizedBox(height: 6),
            const Text(
              'Rien n\'est perdu : elles sont enregistrées sur cet appareil et '
              'le resteront jusqu\'à ce qu\'elles partent.',
              style: TextStyle(
                fontSize:   12.5,
                fontFamily: 'Poppins',
                color:      Colors.black87,
              ),
            ),
            const SizedBox(height: 16),
            Flexible(
              child: ListView(
                shrinkWrap: true,
                children: [
                  for (final e in groupes.entries)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 5),
                      child: Row(
                        children: [
                          const Icon(Icons.person_outline_rounded, size: 17),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              e.key,
                              style: const TextStyle(
                                fontSize:   13,
                                fontFamily: 'Poppins',
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                          ),
                          Text(
                            e.value == 1 ? '1 note' : '${e.value} notes',
                            style: const TextStyle(
                              fontSize:   12,
                              fontFamily: 'Poppins',
                              color:      Colors.black54,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],
              ),
            ),
            if (motifs.isNotEmpty) ...[
              const SizedBox(height: 14),
              const Text(
                'Motif indiqué par le serveur',
                style: TextStyle(
                  fontSize:   11,
                  fontFamily: 'Poppins',
                  fontWeight: FontWeight.w700,
                  color:      Colors.black54,
                ),
              ),
              const SizedBox(height: 4),
              for (final m in motifs)
                Padding(
                  padding: const EdgeInsets.only(top: 2),
                  child: Text(
                    '• $m',
                    style: const TextStyle(
                      fontSize:   12,
                      fontFamily: 'Poppins',
                      color:      Colors.black87,
                    ),
                  ),
                ),
            ],
            const SizedBox(height: 18),
            // Le bouton ne peut RIEN faire hors ligne : le proposer quand même
            // ferait disparaître l'alerte sans que rien ne soit parti.
            BlocBuilder<OfflineBloc, OfflineState>(
              builder: (context, st) => Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  SizedBox(
                    width: double.infinity,
                    child: FilledButton.icon(
                      onPressed: st.isOnline
                          ? () {
                              context
                                  .read<OfflineBloc>()
                                  .add(const OfflineRetryBlockedRequested());
                              Navigator.of(sheetContext).pop();
                            }
                          : null,
                      icon:  const Icon(Icons.refresh_rounded, size: 18),
                      label: const Text(
                        'Réessayer l\'envoi',
                        style: TextStyle(
                            fontFamily: 'Poppins', fontWeight: FontWeight.w600),
                      ),
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    st.isOnline
                        ? 'Si l\'envoi échoue encore, prévenez le responsable de '
                          'la matière sans quitter l\'application.'
                        : 'Pas de réseau pour le moment. Vos notes restent ici ; '
                          'réessayez dès que la connexion revient.',
                    style: const TextStyle(
                      fontSize:   11,
                      fontFamily: 'Poppins',
                      color:      Colors.black54,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    ),
  );
}

// ── Indicateur compact pour l'AppBar ─────────────────────────────────────────

/// Badge discret à intégrer dans l'AppBar de GradingScreen.
/// Affiche le nombre de notes non synchronisées.
class OfflinePendingBadge extends StatelessWidget {
  const OfflinePendingBadge({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<OfflineBloc, OfflineState>(
      buildWhen: (prev, curr) =>
          prev.pendingCount != curr.pendingCount ||
          prev.isOnline     != curr.isOnline,
      builder: (context, state) {
        if (state.isOnline && state.pendingCount == 0) {
          return const SizedBox.shrink();
        }
        return Tooltip(
          message: state.isOnline
              ? '${state.pendingCount} note(s) en cours de sync'
              : '${state.pendingCount} note(s) hors-ligne',
          child: GestureDetector(
            onTap: state.isOnline
                ? () => context
                    .read<OfflineBloc>()
                    .add(const OfflineSyncRequested())
                : null,
            child: Container(
              margin:  const EdgeInsets.only(right: 12),
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color:        state.isOnline
                    ? AppTheme.scoreOrange.withOpacity(0.85)
                    : Colors.white.withOpacity(0.20),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    state.isOnline
                        ? Icons.sync_rounded
                        : Icons.cloud_off_outlined,
                    color: Colors.white,
                    size:  13,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    '${state.pendingCount}',
                    style: const TextStyle(
                      color:      Colors.white,
                      fontSize:   12,
                      fontWeight: FontWeight.w700,
                      fontFamily: 'Poppins',
                    ),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}