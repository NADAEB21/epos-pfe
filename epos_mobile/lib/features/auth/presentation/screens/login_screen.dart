// lib/features/auth/presentation/screens/login_screen.dart
// ════════════════════════════════════════════════
// CORRECTIONS v3 :
//   1. Thème sombre : fond, champs, boutons lisent Theme.of(context)
//   2. Traductions minimales : labels champs, bouton, lien, pied de page
//      lus depuis ProfileBloc
//   3. Navigation après logout gérée par app.dart (navigatorKey) —
//      LoginScreen n'a plus besoin de naviguer lui-même
// BF1.3 — Le lien "Mot de passe oublié ?" ouvre désormais ForgotPasswordScreen,
//      qui réutilise l'AuthBloc déjà fourni par app.dart.

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../profile/domain/entities/profile_settings.dart';
import '../../../profile/presentation/bloc/profile_bloc.dart';
import '../bloc/auth_bloc.dart';
import 'forgot_password_screen.dart';

// ════════════════════════════════════════════════
// TRADUCTIONS LOGIN
// ════════════════════════════════════════════════
class _L {
  static String username(AppLanguage l) => {
    AppLanguage.french:  "Nom d'utilisateur",
    AppLanguage.english: 'Username',
    AppLanguage.arabic:  'اسم المستخدم',
  }[l]!;

  static String password(AppLanguage l) => {
    AppLanguage.french:  'Mot de passe',
    AppLanguage.english: 'Password',
    AppLanguage.arabic:  'كلمة المرور',
  }[l]!;

  static String forgotPassword(AppLanguage l) => {
    AppLanguage.french:  'Mot de passe oublié ?',
    AppLanguage.english: 'Forgot password?',
    AppLanguage.arabic:  'نسيت كلمة المرور؟',
  }[l]!;

  static String login(AppLanguage l) => {
    AppLanguage.french:  'Se connecter',
    AppLanguage.english: 'Log in',
    AppLanguage.arabic:  'تسجيل الدخول',
  }[l]!;

  static String footer(AppLanguage l) => {
    AppLanguage.french:  'Faculté de Pharmacie de Monastir',
    AppLanguage.english: 'Faculty of Pharmacy of Monastir',
    AppLanguage.arabic:  'كلية الصيدلة بالمنستير',
  }[l]!;

  static String subtitle(AppLanguage l) => {
    AppLanguage.french:  'Evaluation Platform for Operational Skills',
    AppLanguage.english: 'Evaluation Platform for Operational Skills',
    AppLanguage.arabic:  'منصة تقييم المهارات التشغيلية',
  }[l]!;

  static String errorEmpty(AppLanguage l) => {
    AppLanguage.french:  "Veuillez saisir votre nom d'utilisateur.",
    AppLanguage.english: 'Please enter your username.',
    AppLanguage.arabic:  'يرجى إدخال اسم المستخدم.',
  }[l]!;

  static String errorPasswordEmpty(AppLanguage l) => {
    AppLanguage.french:  'Veuillez saisir votre mot de passe.',
    AppLanguage.english: 'Please enter your password.',
    AppLanguage.arabic:  'يرجى إدخال كلمة المرور.',
  }[l]!;

  static String errorPasswordShort(AppLanguage l) => {
    AppLanguage.french:  'Le mot de passe doit contenir au moins 8 caractères.',
    AppLanguage.english: 'Password must be at least 8 characters.',
    AppLanguage.arabic:  'كلمة المرور يجب أن تحتوي على 8 أحرف على الأقل.',
  }[l]!;
}

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey              = GlobalKey<FormState>();
  final _emailController      = TextEditingController();
  final _passwordController   = TextEditingController();
  bool  _passwordVisible      = false;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  void _onSubmit() {
    FocusScope.of(context).unfocus();
    if (!_formKey.currentState!.validate()) return;
    context.read<AuthBloc>().add(
      AuthLoginRequested(
        email:    _emailController.text,
        password: _passwordController.text,
      ),
    );
  }

  /// BF1.3 — Ouvre l'écran "mot de passe oublié" en réutilisant l'AuthBloc
  /// courant (même pattern que la navigation vers HomeScreen/ProfileScreen).
  void _onForgotPassword() {
    final authBloc = context.read<AuthBloc>();
    Navigator.of(context, rootNavigator: true).push(MaterialPageRoute(
      builder: (_) => BlocProvider.value(
        value: authBloc,
        child: const ForgotPasswordScreen(),
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    // Lit la langue depuis ProfileBloc (disponible au niveau racine)
    final profileState = context.watch<ProfileBloc>().state;
    final lang         = profileState.settings.language;
    final isDark       = profileState.settings.themeMode == AppThemeMode.dark;

    final bgColor      = isDark ? const Color(0xFF1A1F14) : AppTheme.background;
    final titleColor   = isDark ? AppTheme.primaryLight   : AppTheme.primaryDark;
    final subtitleColor = isDark ? const Color(0xFF9E9E9E) : AppTheme.textSecondary;

    return Scaffold(
      backgroundColor: bgColor,
      body: BlocListener<AuthBloc, AuthState>(
        listener: (context, state) {
          // Navigation vers HomeScreen gérée par _RootNavigator dans app.dart
          if (state is AuthFailure) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(
                SnackBar(
                  content: Row(
                    children: [
                      const Icon(
                        Icons.error_outline, color: Colors.white, size: 20,
                      ),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          state.message,
                          style: const TextStyle(
                            fontFamily: 'Poppins', fontSize: 13,
                          ),
                        ),
                      ),
                    ],
                  ),
                  backgroundColor: AppTheme.scoreRed,
                  behavior: SnackBarBehavior.floating,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(10),
                  ),
                  margin:   const EdgeInsets.all(16),
                  duration: const Duration(seconds: 4),
                ),
              );
          }
        },
        child: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: 32),
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const SizedBox(height: 24),

                  _Logo(isDark: isDark),

                  const SizedBox(height: 20),

                  _AppTitle(
                    lang:         lang,
                    titleColor:   titleColor,
                    subtitleColor: subtitleColor,
                  ),

                  const SizedBox(height: 40),

                  _LoginForm(
                    formKey:            _formKey,
                    emailController:    _emailController,
                    passwordController: _passwordController,
                    passwordVisible:    _passwordVisible,
                    lang:               lang,
                    isDark:             isDark,
                    onTogglePassword: () =>
                        setState(() => _passwordVisible = !_passwordVisible),
                    onSubmit: _onSubmit,
                    onForgotPassword: _onForgotPassword,
                  ),

                  const SizedBox(height: 48),

                  _Footer(lang: lang, subtitleColor: subtitleColor),

                  const SizedBox(height: 24),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// ════════════════════════════════════════════════
// SOUS-WIDGETS
// ════════════════════════════════════════════════

class _Logo extends StatelessWidget {
  final bool isDark;
  const _Logo({required this.isDark});

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 110, height: 110,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: isDark ? const Color(0xFF252B1E) : AppTheme.surface,
        boxShadow: [
          BoxShadow(
            color:      AppTheme.primary.withValues(alpha: 0.15),
            blurRadius: 20,
            offset:     const Offset(0, 6),
          ),
        ],
        border: Border.all(
          color: AppTheme.primary.withValues(alpha: 0.2), width: 2,
        ),
      ),
      child: ClipOval(
        child: Image.asset(
          'assets/images/logo_pharmacie.png',
          fit: BoxFit.cover,
          errorBuilder: (_, __, ___) => Container(
            color: AppTheme.primaryDark,
            child: const Center(
              child: Text(
                'FPM',
                style: TextStyle(
                  color: Colors.white, fontSize: 22,
                  fontWeight: FontWeight.bold, fontFamily: 'Poppins',
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _AppTitle extends StatelessWidget {
  final AppLanguage lang;
  final Color       titleColor;
  final Color       subtitleColor;

  const _AppTitle({
    required this.lang,
    required this.titleColor,
    required this.subtitleColor,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(
          'EPOS',
          style: TextStyle(
            fontSize: 32, fontWeight: FontWeight.w700,
            color: titleColor, fontFamily: 'Poppins', letterSpacing: 2,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          _L.subtitle(lang),
          textAlign: TextAlign.center,
          style: TextStyle(
            fontSize: 12, fontStyle: FontStyle.italic,
            color: subtitleColor, fontFamily: 'Poppins', height: 1.4,
          ),
        ),
      ],
    );
  }
}

class _LoginForm extends StatelessWidget {
  final GlobalKey<FormState>  formKey;
  final TextEditingController emailController;
  final TextEditingController passwordController;
  final bool                  passwordVisible;
  final AppLanguage           lang;
  final bool                  isDark;
  final VoidCallback          onTogglePassword;
  final VoidCallback          onSubmit;
  final VoidCallback          onForgotPassword;

  const _LoginForm({
    required this.formKey,
    required this.emailController,
    required this.passwordController,
    required this.passwordVisible,
    required this.lang,
    required this.isDark,
    required this.onTogglePassword,
    required this.onSubmit,
    required this.onForgotPassword,
  });

  @override
  Widget build(BuildContext context) {
    final textColor = isDark ? Colors.white : AppTheme.textPrimary;
    final hintColor = isDark ? const Color(0xFF6E6E6E) : AppTheme.criterionPending;

    return Form(
      key: formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Champ nom d'utilisateur
          _EposTextField(
            controller:      emailController,
            hintText:        _L.username(lang),
            keyboardType:    TextInputType.emailAddress,
            textInputAction: TextInputAction.next,
            prefixIcon:      Icons.person_outline,
            isDark:          isDark,
            textColor:       textColor,
            hintColor:       hintColor,
            validator: (value) {
              if (value == null || value.trim().isEmpty) {
                return _L.errorEmpty(lang);
              }
              return null;
            },
          ),

          const SizedBox(height: 16),

          // Champ mot de passe
          _EposTextField(
            controller:      passwordController,
            hintText:        _L.password(lang),
            obscureText:     !passwordVisible,
            textInputAction: TextInputAction.done,
            prefixIcon:      Icons.lock_outline,
            isDark:          isDark,
            textColor:       textColor,
            hintColor:       hintColor,
            onFieldSubmitted: (_) => onSubmit(),
            suffixIcon: IconButton(
              icon: Icon(
                passwordVisible
                    ? Icons.visibility_outlined
                    : Icons.visibility_off_outlined,
                color: isDark
                    ? const Color(0xFF9E9E9E)
                    : AppTheme.textSecondary,
                size: 20,
              ),
              onPressed: onTogglePassword,
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return _L.errorPasswordEmpty(lang);
              }
              if (value.length < 8) {
                return _L.errorPasswordShort(lang);
              }
              return null;
            },
          ),

          const SizedBox(height: 12),

          // Lien mot de passe oublié
          Align(
            alignment: Alignment.centerRight,
            child: GestureDetector(
              onTap: onForgotPassword,
              child: Text(
                _L.forgotPassword(lang),
                style: TextStyle(
                  fontSize:   13,
                  color:      AppTheme.primaryLight,
                  fontFamily: 'Poppins',
                  decoration: TextDecoration.underline,
                  decorationColor: AppTheme.primaryLight,
                ),
              ),
            ),
          ),

          const SizedBox(height: 28),

          // Bouton Se connecter
          BlocBuilder<AuthBloc, AuthState>(
            builder: (context, state) {
              final isLoading = state is AuthLoading;
              return AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                height: 52,
                child: ElevatedButton(
                  onPressed: isLoading ? null : onSubmit,
                  style: ElevatedButton.styleFrom(
                    backgroundColor: isLoading
                        ? AppTheme.primary.withValues(alpha: 0.7)
                        : AppTheme.primary,
                    foregroundColor: Colors.white,
                    elevation:   isLoading ? 0 : 3,
                    shadowColor: AppTheme.primary.withValues(alpha: 0.4),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(10),
                    ),
                  ),
                  child: isLoading
                      ? const SizedBox(
                    width: 22, height: 22,
                    child: CircularProgressIndicator(
                      strokeWidth: 2.5,
                      valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
                    ),
                  )
                      : Text(
                    _L.login(lang),
                    style: const TextStyle(
                      fontSize: 16, fontWeight: FontWeight.w600,
                      fontFamily: 'Poppins', letterSpacing: 0.5,
                    ),
                  ),
                ),
              );
            },
          ),
        ],
      ),
    );
  }
}

class _EposTextField extends StatelessWidget {
  final TextEditingController    controller;
  final String                   hintText;
  final bool                     obscureText;
  final TextInputType?           keyboardType;
  final TextInputAction?         textInputAction;
  final IconData                 prefixIcon;
  final Widget?                  suffixIcon;
  final bool                     isDark;
  final Color                    textColor;
  final Color                    hintColor;
  final String? Function(String?)? validator;
  final void Function(String)?   onFieldSubmitted;

  const _EposTextField({
    required this.controller,
    required this.hintText,
    required this.prefixIcon,
    required this.isDark,
    required this.textColor,
    required this.hintColor,
    this.obscureText        = false,
    this.keyboardType,
    this.textInputAction,
    this.suffixIcon,
    this.validator,
    this.onFieldSubmitted,
  });

  @override
  Widget build(BuildContext context) {
    final fillColor =
    isDark ? const Color(0xFF2C3322) : AppTheme.surface;
    final borderNormal =
    isDark ? const Color(0xFF3D4A30) : const Color(0xFFDDD8CC);

    return TextFormField(
      controller:       controller,
      obscureText:      obscureText,
      keyboardType:     keyboardType,
      textInputAction:  textInputAction,
      onFieldSubmitted: onFieldSubmitted,
      validator:        validator,
      style: TextStyle(fontFamily: 'Poppins', fontSize: 14, color: textColor),
      decoration: InputDecoration(
        hintText:  hintText,
        hintStyle: TextStyle(
          fontFamily: 'Poppins', fontSize: 14, color: hintColor,
        ),
        filled:    true,
        fillColor: fillColor,
        prefixIcon: Icon(
          prefixIcon,
          color: isDark ? const Color(0xFF9E9E9E) : AppTheme.textSecondary,
          size:  20,
        ),
        suffixIcon:     suffixIcon,
        contentPadding: const EdgeInsets.symmetric(
          horizontal: 16, vertical: 16,
        ),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide:   BorderSide(color: borderNormal),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide:   BorderSide(color: borderNormal),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide:
          const BorderSide(color: AppTheme.primary, width: 2),
        ),
        errorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide:   const BorderSide(color: AppTheme.scoreRed),
        ),
        focusedErrorBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(10),
          borderSide:
          const BorderSide(color: AppTheme.scoreRed, width: 2),
        ),
        errorStyle: const TextStyle(fontFamily: 'Poppins', fontSize: 11),
      ),
    );
  }
}

class _Footer extends StatelessWidget {
  final AppLanguage lang;
  final Color       subtitleColor;

  const _Footer({required this.lang, required this.subtitleColor});

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          width: 40, height: 1,
          color: AppTheme.criterionPending,
        ),
        const SizedBox(height: 12),
        Text(
          _L.footer(lang),
          style: TextStyle(
            fontSize:   11,
            color:      subtitleColor,
            fontFamily: 'Poppins',
          ),
        ),
      ],
    );
  }
}