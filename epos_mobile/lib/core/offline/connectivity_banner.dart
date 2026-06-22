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
        // Bannière persistante hors-ligne ou pendant la sync
        if (!state.isOnline || state.isSyncing) {
          _showBanner();
        } else {
          _hideBanner();
        }

        // Toast de résultat de synchronisation
        if (state.syncStatus == SyncStatus.success && state.lastSyncedCount > 0) {
          _showSyncToast(context, state);
        } else if (state.syncStatus == SyncStatus.partialFailure) {
          _showPartialFailureToast(context, state);
        }
      },
      buildWhen: (prev, curr) =>
          prev.isOnline   != curr.isOnline  ||
          prev.isSyncing  != curr.isSyncing ||
          prev.pendingCount != curr.pendingCount,
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
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Row(
          children: [
            const Icon(Icons.cloud_done_outlined, color: Colors.white, size: 18),
            const SizedBox(width: 10),
            Text(
              '${state.lastSyncedCount} notation(s) synchronisée(s)',
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

  void _showPartialFailureToast(BuildContext context, OfflineState state) {
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(
        content: Row(
          children: [
            const Icon(Icons.warning_amber_rounded, color: Colors.white, size: 18),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                'Sync partielle : ${state.lastSyncedCount} OK. '
                '${state.pendingCount} notation(s) en attente.',
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
    if (!state.isOnline) {
      return _OfflineBanner(pendingCount: state.pendingCount);
    }
    return const SizedBox.shrink();
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