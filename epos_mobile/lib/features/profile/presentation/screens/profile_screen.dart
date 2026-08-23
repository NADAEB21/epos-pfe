// lib/features/profile/presentation/screens/profile_screen.dart
// ════════════════════════════════════════════════
// FIX 1 — Suppression du Theme() local et _buildDarkTheme() dupliqué.
//   Le thème est maintenant géré UNIQUEMENT par MaterialApp dans app.dart.
//   ProfileScreen lit juste isDark pour ajuster ses couleurs de surface.
//
// FIX 2 — Déconnexion via navigatorKey.
//   Le dialog de confirmation utilise navigatorKey.currentState pour
//   fermer le dialog PUIS déclencher le logout — la redirection vers
//   LoginScreen est gérée par le BlocListener dans app.dart.
// ════════════════════════════════════════════════

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../app.dart'; // buildDarkTheme
import '../../../../core/theme/app_theme.dart';
import '../../../auth/domain/entities/user.dart';
import '../../../auth/presentation/bloc/auth_bloc.dart';
import '../../domain/entities/profile_settings.dart';
import '../bloc/profile_bloc.dart';

// ════════════════════════════════════════════════
// TRADUCTIONS INLINE
// ════════════════════════════════════════════════
class _T {
  static const Map<String, Map<String, String>> _data = {
    'fr': {
      'profil':           'Mon Profil',
      'parametre':        'Paramètre',
      'mon_compte':       'Mon Compte',
      'infos_perso':      'Informations personnelles',
      'langue':           'Langue',
      'theme':            'Thème',
      'deconnexion':      'Se déconnecter',
      'confirm_deco':     'Déconnexion',
      'confirm_deco_msg': 'Êtes-vous sûr de vouloir vous déconnecter ?',
      'annuler':          'Annuler',
      'deconnecter':      'Déconnecter',
      'nom':              'Nom',
      'email':            'Email',
      'poste':            'Poste',
      'evaluateur':       'Évaluateur',
      'changer_mdp':      'Changer le mot de passe',
      'mdp_actuel':       'Mot de passe actuel',
      'nouveau_mdp':      'Nouveau mot de passe',
      'confirmer_mdp':    'Confirmer le mot de passe',
      'min_8':            'Min. 8 caractères',
      'enregistrer':      'Enregistrer',
      'choisir_langue':   'Choisir la langue',
      'choisir_theme':    'Choisir le thème',
      'mdp_modifie':      'Mot de passe modifié avec succès.',
      'mdp_no_match':     'Les mots de passe ne correspondent pas.',
    },
    'en': {
      'profil':           'My Profile',
      'parametre':        'Settings',
      'mon_compte':       'My Account',
      'infos_perso':      'Personal information',
      'langue':           'Language',
      'theme':            'Theme',
      'deconnexion':      'Log out',
      'confirm_deco':     'Log out',
      'confirm_deco_msg': 'Are you sure you want to log out?',
      'annuler':          'Cancel',
      'deconnecter':      'Log out',
      'nom':              'Name',
      'email':            'Email',
      'poste':            'Role',
      'evaluateur':       'Evaluator',
      'changer_mdp':      'Change password',
      'mdp_actuel':       'Current password',
      'nouveau_mdp':      'New password',
      'confirmer_mdp':    'Confirm password',
      'min_8':            'Min. 8 characters',
      'enregistrer':      'Save',
      'choisir_langue':   'Choose language',
      'choisir_theme':    'Choose theme',
      'mdp_modifie':      'Password changed successfully.',
      'mdp_no_match':     'Passwords do not match.',
    },
    'ar': {
      'profil':           'ملفي الشخصي',
      'parametre':        'الإعدادات',
      'mon_compte':       'حسابي',
      'infos_perso':      'المعلومات الشخصية',
      'langue':           'اللغة',
      'theme':            'المظهر',
      'deconnexion':      'تسجيل الخروج',
      'confirm_deco':     'تسجيل الخروج',
      'confirm_deco_msg': 'هل أنت متأكد أنك تريد تسجيل الخروج؟',
      'annuler':          'إلغاء',
      'deconnecter':      'خروج',
      'nom':              'الاسم',
      'email':            'البريد الإلكتروني',
      'poste':            'المنصب',
      'evaluateur':       'مقيّم',
      'changer_mdp':      'تغيير كلمة المرور',
      'mdp_actuel':       'كلمة المرور الحالية',
      'nouveau_mdp':      'كلمة المرور الجديدة',
      'confirmer_mdp':    'تأكيد كلمة المرور',
      'min_8':            'الحد الأدنى 8 أحرف',
      'enregistrer':      'حفظ',
      'choisir_langue':   'اختر اللغة',
      'choisir_theme':    'اختر المظهر',
      'mdp_modifie':      'تم تغيير كلمة المرور بنجاح.',
      'mdp_no_match':     'كلمتا المرور غير متطابقتين.',
    },
  };

  static String get(AppLanguage lang, String key) =>
      _data[lang.code]?[key] ?? _data['fr']![key] ?? key;
}

// ════════════════════════════════════════════════
// ÉCRAN PRINCIPAL
// FIX 1 : Plus de Theme() local — le thème vient
// directement de MaterialApp via app.dart
// ════════════════════════════════════════════════
class ProfileScreen extends StatelessWidget {
  const ProfileScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<ProfileBloc, ProfileState>(
      builder: (context, state) {
        final isDark = state.settings.themeMode == AppThemeMode.dark;
        final lang   = state.settings.language;

        // FIX 1 : Plus de Theme() ici — MaterialApp gère déjà le thème global
        return Scaffold(
          backgroundColor: isDark
              ? const Color(0xFF1A1F14)
              : AppTheme.background,
          body: BlocListener<ProfileBloc, ProfileState>(
            listener: (context, listenerState) {
              if (listenerState is ProfilePasswordChanged) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                  content:         Text(_T.get(lang, 'mdp_modifie')),
                  backgroundColor: AppTheme.scoreGreen,
                  behavior:        SnackBarBehavior.floating,
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10)),
                  margin: const EdgeInsets.all(16),
                ));
                context
                    .read<ProfileBloc>()
                    .add(const ProfilePasswordChangeDismissed());
              }
              if (listenerState is ProfilePasswordError) {
                ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                  content:         Text(listenerState.message),
                  backgroundColor: AppTheme.scoreRed,
                  behavior:        SnackBarBehavior.floating,
                  shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10)),
                  margin: const EdgeInsets.all(16),
                ));
                context
                    .read<ProfileBloc>()
                    .add(const ProfilePasswordChangeDismissed());
              }
            },
            child: BlocBuilder<AuthBloc, AuthState>(
              builder: (context, authState) {
                final user = authState is AuthAuthenticated
                    ? authState.user
                    : null;
                return CustomScrollView(
                  slivers: [
                    SliverToBoxAdapter(
                      child: _ProfileHeader(
                        user:   user,
                        isDark: isDark,
                        lang:   lang,
                      ),
                    ),
                    SliverToBoxAdapter(
                      child: _ProfileBody(
                        user:   user,
                        state:  state,
                        isDark: isDark,
                        lang:   lang,
                      ),
                    ),
                  ],
                );
              },
            ),
          ),
        );
      },
    );
  }
}

// ════════════════════════════════════════════════
// HEADER
// ════════════════════════════════════════════════
class _ProfileHeader extends StatelessWidget {
  final User?       user;
  final bool        isDark;
  final AppLanguage lang;

  const _ProfileHeader({
    required this.user,
    required this.isDark,
    required this.lang,
  });

  @override
  Widget build(BuildContext context) {
    final headerBg =
        isDark ? const Color(0xFF1A2610) : AppTheme.primaryDark;

    return Container(
      width: double.infinity,
      decoration: BoxDecoration(
        color: headerBg,
        borderRadius: const BorderRadius.only(
          bottomLeft:  Radius.circular(24),
          bottomRight: Radius.circular(24),
        ),
      ),
      padding: EdgeInsets.only(
        top:    MediaQuery.of(context).padding.top + 16,
        left:   20, right: 20, bottom: 32,
      ),
      child: Column(
        children: [
          Row(
            children: [
              GestureDetector(
                onTap: () => Navigator.of(context, rootNavigator: true).pop(),
                child: Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.15),
                    borderRadius: BorderRadius.circular(10),
                  ),
                  child: const Icon(
                    Icons.arrow_back,
                    color: Colors.white,
                    size:  20,
                  ),
                ),
              ),
              const SizedBox(width: 12),
              Text(
                _T.get(lang, 'profil'),
                style: const TextStyle(
                  color: Colors.white, fontSize: 18, fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
          const SizedBox(height: 28),
          Container(
            width: 88, height: 88,
            decoration: BoxDecoration(
              color: AppTheme.accent,
              shape: BoxShape.circle,
              border: Border.all(
                color: Colors.white.withValues(alpha: 0.3), width: 3,
              ),
              boxShadow: [
                BoxShadow(
                  color:      Colors.black.withValues(alpha: 0.2),
                  blurRadius: 16,
                  offset:     const Offset(0, 6),
                ),
              ],
            ),
            child: Center(
              child: Text(
                user?.initiales ?? '??',
                style: const TextStyle(
                  color: Colors.white, fontSize: 30, fontWeight: FontWeight.w700,
                ),
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            user != null ? 'Dr ${user!.nomComplet}' : '',
            style: const TextStyle(
              color: Colors.white, fontSize: 16, fontWeight: FontWeight.w600,
            ),
          ),
          if (user?.email != null) ...[
            const SizedBox(height: 4),
            Text(
              user!.email,
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.70), fontSize: 13,
              ),
            ),
          ],
          // W4 — les rôles RÉELS sous l'identité (« Responsable de matière ·
          // Évaluateur ») : la personne sait ce que la plateforme sait d'elle.
          if (user != null) ...[
            const SizedBox(height: 6),
            Text(
              user!.displayRoles,
              textAlign: TextAlign.center,
              style: TextStyle(
                color: Colors.white.withValues(alpha: 0.85),
                fontSize: 12.5, fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

// ════════════════════════════════════════════════
// BODY
// ════════════════════════════════════════════════
class _ProfileBody extends StatelessWidget {
  final User?        user;
  final ProfileState state;
  final bool         isDark;
  final AppLanguage  lang;

  const _ProfileBody({
    required this.user,
    required this.state,
    required this.isDark,
    required this.lang,
  });

  @override
  Widget build(BuildContext context) {
    final surfaceColor  = isDark ? const Color(0xFF252B1E) : AppTheme.surface;
    final borderColor   = isDark ? const Color(0xFF3D4A30) : const Color(0xFFEEEBE3);
    final textPrimColor = isDark ? Colors.white : AppTheme.textPrimary;
    final textSecColor  = isDark ? const Color(0xFF9E9E9E) : AppTheme.textSecondary;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _SectionCard(
            title:        _T.get(lang, 'parametre'),
            isDark:       isDark,
            surfaceColor: surfaceColor,
            borderColor:  borderColor,
            children: [
              _SettingsTile(
                icon:          Icons.person_outline,
                iconColor:     const Color(0xFF5C8ED4),
                title:         _T.get(lang, 'mon_compte'),
                subtitle:      _T.get(lang, 'infos_perso'),
                textPrimary:   textPrimColor,
                textSecondary: textSecColor,
                onTap: () => _showAccountDialog(context, user, lang, isDark),
              ),
              _Divider(color: borderColor),
              _SettingsTile(
                icon:          Icons.language_outlined,
                iconColor:     const Color(0xFF4BACC6),
                title:         _T.get(lang, 'langue'),
                subtitle:      state.settings.language.label,
                textPrimary:   textPrimColor,
                textSecondary: textSecColor,
                onTap: () => _showLanguageDialog(
                  context, state.settings.language, lang, isDark,
                ),
              ),
              // W4 — l'interrupteur « Notifications » est SUPPRIMÉ : il ne
              // pilotait rien (aucun plugin, aucun consommateur du booléen) —
              // il animait son propre libellé. Il reviendra avec un vrai canal.
              _Divider(color: borderColor),
              _SettingsTile(
                icon:          Icons.brightness_6_outlined,
                iconColor:     const Color(0xFFE6AC00),
                title:         _T.get(lang, 'theme'),
                subtitle:      state.settings.themeMode == AppThemeMode.light
                    ? 'Clair (défaut)'
                    : 'Sombre',
                textPrimary:   textPrimColor,
                textSecondary: textSecColor,
                onTap: () => _showThemeDialog(
                  context, state.settings.themeMode, lang, isDark,
                ),
              ),
            ],
          ),

          const SizedBox(height: 32),

          _LogoutButton(
            label:        _T.get(lang, 'deconnexion'),
            surfaceColor: surfaceColor,
            borderColor:  borderColor,
            // FIX 2 : on passe authBloc ET profileBloc en paramètre
            onTap: () => _confirmLogout(context, lang, isDark),
          ),

          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

// ════════════════════════════════════════════════
// COMPOSANTS UI
// ════════════════════════════════════════════════
class _SectionCard extends StatelessWidget {
  final String       title;
  final bool         isDark;
  final Color        surfaceColor;
  final Color        borderColor;
  final List<Widget> children;

  const _SectionCard({
    required this.title,
    required this.isDark,
    required this.surfaceColor,
    required this.borderColor,
    required this.children,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          decoration: BoxDecoration(
            color: isDark ? const Color(0xFF1A2610) : AppTheme.primaryDark,
            borderRadius: const BorderRadius.only(
              topLeft:  Radius.circular(12),
              topRight: Radius.circular(12),
            ),
          ),
          child: Text(
            title,
            style: const TextStyle(
              color: Colors.white, fontSize: 14,
              fontWeight: FontWeight.w700, letterSpacing: 0.3,
            ),
          ),
        ),
        Container(
          decoration: BoxDecoration(
            color: surfaceColor,
            borderRadius: const BorderRadius.only(
              bottomLeft:  Radius.circular(12),
              bottomRight: Radius.circular(12),
            ),
            border: Border.all(color: borderColor),
            boxShadow: [
              BoxShadow(
                color:      Colors.black.withValues(alpha: 0.04),
                blurRadius: 8,
                offset:     const Offset(0, 2),
              ),
            ],
          ),
          child: Column(children: children),
        ),
      ],
    );
  }
}

class _SettingsTile extends StatelessWidget {
  final IconData     icon;
  final Color        iconColor;
  final String       title;
  final String       subtitle;
  final Color        textPrimary;
  final Color        textSecondary;
  final VoidCallback? onTap;

  const _SettingsTile({
    required this.icon,
    required this.iconColor,
    required this.title,
    required this.subtitle,
    required this.textPrimary,
    required this.textSecondary,
    this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(4),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          children: [
            Container(
              width: 36, height: 36,
              decoration: BoxDecoration(
                color: iconColor.withValues(alpha: 0.12),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(icon, color: iconColor, size: 20),
            ),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 14, fontWeight: FontWeight.w600,
                      color: textPrimary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(subtitle,
                      style: TextStyle(fontSize: 12, color: textSecondary)),
                ],
              ),
            ),
            Icon(Icons.chevron_right, color: textSecondary, size: 20),
          ],
        ),
      ),
    );
  }
}

class _Divider extends StatelessWidget {
  final Color color;
  const _Divider({required this.color});

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(left: 66),
    child: Divider(height: 1, color: color),
  );
}

class _LogoutButton extends StatelessWidget {
  final String       label;
  final Color        surfaceColor;
  final Color        borderColor;
  final VoidCallback onTap;

  const _LogoutButton({
    required this.label,
    required this.surfaceColor,
    required this.borderColor,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: surfaceColor,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: borderColor),
        boxShadow: [
          BoxShadow(
            color:      Colors.black.withValues(alpha: 0.04),
            blurRadius: 8,
            offset:     const Offset(0, 2),
          ),
        ],
      ),
      child: InkWell(
        onTap:        onTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16),
          child: Center(
            child: Text(
              label,
              style: const TextStyle(
                fontSize: 15, fontWeight: FontWeight.w600,
                color: AppTheme.scoreRed,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// ════════════════════════════════════════════════
// DIALOGUES
// ════════════════════════════════════════════════

// FIX 2 — Déconnexion via navigatorKey
// Le navigatorKey.currentState permet de vider toute la pile
// depuis le dialog, indépendamment du context du dialog
void _confirmLogout(BuildContext context, AppLanguage lang, bool isDark) {
  final authBloc = context.read<AuthBloc>();

  showDialog<void>(
    context: context,
    builder: (dialogCtx) => Theme(
      // On utilise buildDarkTheme() importé depuis app.dart
      data: isDark ? buildDarkTheme() : AppTheme.lightTheme,
      child: AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          _T.get(lang, 'confirm_deco'),
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        content: Text(
          _T.get(lang, 'confirm_deco_msg'),
          style: const TextStyle(fontSize: 14),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogCtx).pop(),
            child: Text(_T.get(lang, 'annuler')),
          ),
          TextButton(
            onPressed: () {
              // 1. Fermer le dialog
              Navigator.of(dialogCtx).pop();
              // 2. Déclencher le logout → AuthBloc émet AuthUnauthenticated
              //    → BlocListener dans app.dart vide la pile via navigatorKey
              authBloc.add(const AuthLogoutRequested());
            },
            child: Text(
              _T.get(lang, 'deconnecter'),
              style: const TextStyle(
                color: AppTheme.scoreRed, fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
    ),
  );
}

void _showAccountDialog(
  BuildContext context,
  User? user,
  AppLanguage lang,
  bool isDark,
) {
  final profileBloc               = context.read<ProfileBloc>();
  final currentPasswordController = TextEditingController();
  final newPasswordController     = TextEditingController();
  final confirmPasswordController = TextEditingController();

  showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (dialogCtx) => Theme(
      data: isDark ? buildDarkTheme() : AppTheme.lightTheme,
      child: StatefulBuilder(
        builder: (ctx, setState) {
          bool obscureCurrent = true;
          bool obscureNew     = true;
          bool obscureConfirm = true;

          return StatefulBuilder(
            builder: (ctx2, setState2) => AlertDialog(
              shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(16)),
              title: Text(
                _T.get(lang, 'mon_compte'),
                style: const TextStyle(
                    fontSize: 17, fontWeight: FontWeight.w700),
              ),
              content: SingleChildScrollView(
                child: Column(
                  mainAxisSize:       MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      _T.get(lang, 'infos_perso'),
                      style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600,
                        color: AppTheme.primaryLight,
                      ),
                    ),
                    const SizedBox(height: 10),
                    _InfoRow(
                      label:  _T.get(lang, 'nom'),
                      value:  user?.nomComplet ?? '—',
                      isDark: isDark,
                    ),
                    _InfoRow(
                      label:  _T.get(lang, 'email'),
                      value:  user?.email ?? '—',
                      isDark: isDark,
                    ),
                    _InfoRow(
                      label:  _T.get(lang, 'poste'),
                      // W4 — le VRAI cumul de rôles, plus un littéral constant :
                      // un responsable qui évalue lisait « Évaluateur » tout
                      // court — mal renseigné sur ses propres droits.
                      // displayRoles avait été écrit pour cette ligne.
                      value:  user?.displayRoles ?? '—',
                      isDark: isDark,
                    ),
                    const SizedBox(height: 20),
                    Divider(
                      color: isDark
                          ? const Color(0xFF3D4A30)
                          : const Color(0xFFEEEBE3),
                    ),
                    const SizedBox(height: 12),
                    Text(
                      _T.get(lang, 'changer_mdp'),
                      style: const TextStyle(
                        fontSize: 13, fontWeight: FontWeight.w600,
                        color: AppTheme.primaryLight,
                      ),
                    ),
                    const SizedBox(height: 10),
                    TextField(
                      controller:  currentPasswordController,
                      obscureText: obscureCurrent,
                      decoration: InputDecoration(
                        labelText: _T.get(lang, 'mdp_actuel'),
                        suffixIcon: IconButton(
                          icon: Icon(
                            obscureCurrent
                                ? Icons.visibility_off_outlined
                                : Icons.visibility_outlined,
                            size: 20,
                          ),
                          onPressed: () => setState2(
                              () => obscureCurrent = !obscureCurrent),
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    TextField(
                      controller:  newPasswordController,
                      obscureText: obscureNew,
                      decoration: InputDecoration(
                        labelText:  _T.get(lang, 'nouveau_mdp'),
                        helperText: _T.get(lang, 'min_8'),
                        suffixIcon: IconButton(
                          icon: Icon(
                            obscureNew
                                ? Icons.visibility_off_outlined
                                : Icons.visibility_outlined,
                            size: 20,
                          ),
                          onPressed: () =>
                              setState2(() => obscureNew = !obscureNew),
                        ),
                      ),
                    ),
                    const SizedBox(height: 10),
                    TextField(
                      controller:  confirmPasswordController,
                      obscureText: obscureConfirm,
                      decoration: InputDecoration(
                        labelText: _T.get(lang, 'confirmer_mdp'),
                        suffixIcon: IconButton(
                          icon: Icon(
                            obscureConfirm
                                ? Icons.visibility_off_outlined
                                : Icons.visibility_outlined,
                            size: 20,
                          ),
                          onPressed: () => setState2(
                              () => obscureConfirm = !obscureConfirm),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(dialogCtx).pop(),
                  child: Text(_T.get(lang, 'annuler')),
                ),
                TextButton(
                  onPressed: () {
                    final current = currentPasswordController.text.trim();
                    final newPwd  = newPasswordController.text.trim();
                    final confirm = confirmPasswordController.text.trim();

                    if (current.isEmpty || newPwd.isEmpty) return;

                    if (newPwd != confirm) {
                      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
                        content:         Text(_T.get(lang, 'mdp_no_match')),
                        backgroundColor: AppTheme.scoreRed,
                        behavior:        SnackBarBehavior.floating,
                        shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(10)),
                        margin: const EdgeInsets.all(16),
                      ));
                      return;
                    }

                    profileBloc.add(ProfilePasswordChangeRequested(
                      currentPassword: current,
                      newPassword:     newPwd,
                    ));
                    Navigator.of(dialogCtx).pop();
                  },
                  child: Text(
                    _T.get(lang, 'enregistrer'),
                    style: const TextStyle(
                      color: AppTheme.primary, fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    ),
  );
}

void _showLanguageDialog(
  BuildContext context,
  AppLanguage current,
  AppLanguage lang,
  bool isDark,
) {
  final profileBloc = context.read<ProfileBloc>();

  showDialog<void>(
    context: context,
    builder: (dialogCtx) => Theme(
      data: isDark ? buildDarkTheme() : AppTheme.lightTheme,
      child: AlertDialog(
        shape:
            RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          _T.get(lang, 'choisir_langue'),
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: AppLanguage.values.map((l) {
            final isSelected = l == current;
            return ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Container(
                width: 36, height: 36,
                decoration: BoxDecoration(
                  color: AppTheme.primary.withValues(alpha: 0.10),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Center(
                  child: Text(
                    _langFlag(l),
                    style: TextStyle(
                      fontSize:   12,
                      fontWeight: FontWeight.w700,
                      color:      isDark ? Colors.white70 : AppTheme.textSecondary,
                    ),
                  ),
                ),
              ),
              title: Text(
                l.label,
                style: TextStyle(
                  fontSize:   14,
                  fontWeight: isSelected ? FontWeight.w700 : FontWeight.w400,
                  color: isSelected
                      ? AppTheme.primaryLight
                      : (isDark ? Colors.white : AppTheme.textPrimary),
                ),
              ),
              trailing: isSelected
                  ? const Icon(Icons.check, color: AppTheme.primary)
                  : null,
              onTap: () {
                profileBloc.add(ProfileLanguageChanged(l));
                Navigator.of(dialogCtx).pop();
              },
            );
          }).toList(),
        ),
      ),
    ),
  );
}

void _showThemeDialog(
  BuildContext context,
  AppThemeMode current,
  AppLanguage lang,
  bool isDark,
) {
  final profileBloc = context.read<ProfileBloc>();

  showDialog<void>(
    context: context,
    builder: (dialogCtx) => Theme(
      data: isDark ? buildDarkTheme() : AppTheme.lightTheme,
      child: AlertDialog(
        shape:
            RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        title: Text(
          _T.get(lang, 'choisir_theme'),
          style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w700),
        ),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          children: AppThemeMode.values.map((t) {
            final isSelected = t == current;
            return ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                t == AppThemeMode.light
                    ? Icons.light_mode_outlined
                    : Icons.dark_mode_outlined,
                color: t == AppThemeMode.light
                    ? const Color(0xFFE6AC00)
                    : const Color(0xFF5C8ED4),
              ),
              title: Text(
                t.label,
                style: TextStyle(
                  fontSize:   14,
                  fontWeight: isSelected ? FontWeight.w700 : FontWeight.w400,
                  color: isSelected
                      ? AppTheme.primaryLight
                      : (isDark ? Colors.white : AppTheme.textPrimary),
                ),
              ),
              trailing: isSelected
                  ? const Icon(Icons.check, color: AppTheme.primary)
                  : null,
              onTap: () {
                profileBloc.add(ProfileThemeChanged(t));
                Navigator.of(dialogCtx).pop();
              },
            );
          }).toList(),
        ),
      ),
    ),
  );
}

String _langFlag(AppLanguage lang) {
  switch (lang) {
    case AppLanguage.french:  return 'FR';
    case AppLanguage.english: return 'EN';
    case AppLanguage.arabic:  return 'AR';
  }
}

class _InfoRow extends StatelessWidget {
  final String label;
  final String value;
  final bool   isDark;

  const _InfoRow({
    required this.label,
    required this.value,
    required this.isDark,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 70,
            child: Text(
              label,
              style: TextStyle(
                fontSize: 12,
                color: isDark
                    ? const Color(0xFF9E9E9E)
                    : AppTheme.textSecondary,
              ),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style: TextStyle(
                fontSize:   13,
                fontWeight: FontWeight.w500,
                color: isDark ? Colors.white : AppTheme.textPrimary,
              ),
            ),
          ),
        ],
      ),
    );
  }
}