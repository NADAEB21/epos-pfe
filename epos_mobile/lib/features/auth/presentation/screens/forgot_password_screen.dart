// lib/features/auth/presentation/screens/forgot_password_screen.dart
// ════════════════════════════════════════════════
// BF1.3 — Récupération de mot de passe.
//
// Flux en 2 étapes, cohérent avec le backend existant
// (POST /auth/password-reset/request puis /confirm) :
//   Étape 1 : l'utilisateur saisit son email → un code/lien est envoyé.
//   Étape 2 : l'utilisateur saisit le code reçu + son nouveau mot de passe.
//
// Note UX : le lien envoyé par email pointe vers le dashboard web
// (MAIL_RESET_BASE_URL, voir application.yml de l'auth-service) car l'app
// mobile ne gère pas encore le deep-linking. En attendant, l'évaluateur
// copie le token affiché dans l'URL du lien et le colle ici. Réutilise le
// même AuthBloc que le reste de l'app (voir auth_bloc.dart, events
// AuthPasswordReset*).
// ════════════════════════════════════════════════

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../core/theme/app_theme.dart';
import '../../../profile/domain/entities/profile_settings.dart';
import '../../../profile/presentation/bloc/profile_bloc.dart';
import '../bloc/auth_bloc.dart';

class _T {
  static String title(AppLanguage l) => {
    AppLanguage.french:  'Mot de passe oublié',
    AppLanguage.english: 'Forgot password',
    AppLanguage.arabic:  'نسيت كلمة المرور',
  }[l]!;

  static String step1Intro(AppLanguage l) => {
    AppLanguage.french:
    "Saisissez l'adresse email associée à votre compte. Un code de "
        'réinitialisation valable 30 minutes vous sera envoyé.',
    AppLanguage.english:
    'Enter the email associated with your account. A reset code valid '
        'for 30 minutes will be sent to you.',
    AppLanguage.arabic:
    'أدخل البريد الإلكتروني المرتبط بحسابك. سيتم إرسال رمز إعادة '
        'التعيين صالح لمدة 30 دقيقة.',
  }[l]!;

  static String step2Intro(AppLanguage l) => {
    AppLanguage.french:
    'Saisissez le code reçu par email ainsi que votre nouveau mot de '
        'passe.',
    AppLanguage.english:
    'Enter the code you received by email along with your new password.',
    AppLanguage.arabic:
    'أدخل الرمز الذي تلقيته عبر البريد الإلكتروني وكلمة المرور '
        'الجديدة.',
  }[l]!;

  static String email(AppLanguage l) => {
    AppLanguage.french:  'Adresse email',
    AppLanguage.english: 'Email address',
    AppLanguage.arabic:  'البريد الإلكتروني',
  }[l]!;

  static String code(AppLanguage l) => {
    AppLanguage.french:  'Code de réinitialisation',
    AppLanguage.english: 'Reset code',
    AppLanguage.arabic:  'رمز إعادة التعيين',
  }[l]!;

  static String newPassword(AppLanguage l) => {
    AppLanguage.french:  'Nouveau mot de passe',
    AppLanguage.english: 'New password',
    AppLanguage.arabic:  'كلمة المرور الجديدة',
  }[l]!;

  static String confirmPassword(AppLanguage l) => {
    AppLanguage.french:  'Confirmer le mot de passe',
    AppLanguage.english: 'Confirm password',
    AppLanguage.arabic:  'تأكيد كلمة المرور',
  }[l]!;

  static String sendCode(AppLanguage l) => {
    AppLanguage.french:  'Envoyer le code',
    AppLanguage.english: 'Send code',
    AppLanguage.arabic:  'إرسال الرمز',
  }[l]!;

  static String reset(AppLanguage l) => {
    AppLanguage.french:  'Réinitialiser',
    AppLanguage.english: 'Reset password',
    AppLanguage.arabic:  'إعادة التعيين',
  }[l]!;

  static String emailSent(AppLanguage l) => {
    AppLanguage.french:
    'Si cette adresse est enregistrée, un code vous a été envoyé.',
    AppLanguage.english:
    'If this address is registered, a code has been sent to you.',
    AppLanguage.arabic:
    'إذا كان هذا العنوان مسجلاً، فقد تم إرسال رمز إليك.',
  }[l]!;

  static String success(AppLanguage l) => {
    AppLanguage.french:
    'Mot de passe réinitialisé avec succès. Vous pouvez vous connecter.',
    AppLanguage.english:
    'Password reset successfully. You can now sign in.',
    AppLanguage.arabic:
    'تمت إعادة تعيين كلمة المرور بنجاح. يمكنك الآن تسجيل الدخول.',
  }[l]!;

  static String mismatch(AppLanguage l) => {
    AppLanguage.french:  'Les mots de passe ne correspondent pas.',
    AppLanguage.english: 'Passwords do not match.',
    AppLanguage.arabic:  'كلمتا المرور غير متطابقتين.',
  }[l]!;

  static String minLength(AppLanguage l) => {
    AppLanguage.french:  'Min. 8 caractères, 1 majuscule, 1 chiffre.',
    AppLanguage.english: 'Min. 8 characters, 1 uppercase, 1 digit.',
    AppLanguage.arabic:  '8 أحرف على الأقل، حرف كبير واحد، رقم واحد.',
  }[l]!;

  static String backToLogin(AppLanguage l) => {
    AppLanguage.french:  'Retour à la connexion',
    AppLanguage.english: 'Back to login',
    AppLanguage.arabic:  'العودة لتسجيل الدخول',
  }[l]!;
}

class ForgotPasswordScreen extends StatefulWidget {
  const ForgotPasswordScreen({super.key});

  @override
  State<ForgotPasswordScreen> createState() => _ForgotPasswordScreenState();
}

class _ForgotPasswordScreenState extends State<ForgotPasswordScreen> {
  final _formKey1 = GlobalKey<FormState>();
  final _formKey2 = GlobalKey<FormState>();

  final _emailController        = TextEditingController();
  final _codeController         = TextEditingController();
  final _newPasswordController  = TextEditingController();
  final _confirmController      = TextEditingController();

  bool _step2 = false;

  @override
  void dispose() {
    _emailController.dispose();
    _codeController.dispose();
    _newPasswordController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  void _submitStep1(AppLanguage lang) {
    if (!_formKey1.currentState!.validate()) return;
    FocusScope.of(context).unfocus();
    context.read<AuthBloc>().add(
      AuthPasswordResetRequested(email: _emailController.text.trim()),
    );
  }

  void _submitStep2(AppLanguage lang) {
    if (!_formKey2.currentState!.validate()) return;
    if (_newPasswordController.text != _confirmController.text) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        content: Text(_T.mismatch(lang)),
        backgroundColor: AppTheme.scoreRed,
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        margin: const EdgeInsets.all(16),
      ));
      return;
    }
    FocusScope.of(context).unfocus();
    context.read<AuthBloc>().add(
      AuthPasswordResetConfirmRequested(
        token:       _codeController.text.trim(),
        newPassword: _newPasswordController.text,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final profileState = context.watch<ProfileBloc>().state;
    final lang   = profileState.settings.language;
    final isDark = profileState.settings.themeMode == AppThemeMode.dark;

    final bgColor    = isDark ? const Color(0xFF1A1F14) : AppTheme.background;
    final titleColor = isDark ? Colors.white : AppTheme.primaryDark;

    return Scaffold(
      backgroundColor: bgColor,
      appBar: AppBar(
        backgroundColor: AppTheme.primaryDark,
        foregroundColor: Colors.white,
        elevation: 0,
        title: Text(
          _T.title(lang),
          style: const TextStyle(fontFamily: 'Poppins', fontWeight: FontWeight.w600),
        ),
      ),
      body: BlocConsumer<AuthBloc, AuthState>(
        listener: (context, state) {
          if (state is AuthFailure) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(SnackBar(
                content: Text(state.message,
                    style: const TextStyle(fontFamily: 'Poppins', fontSize: 13)),
                backgroundColor: AppTheme.scoreRed,
                behavior: SnackBarBehavior.floating,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                margin: const EdgeInsets.all(16),
              ));
          } else if (state is AuthPasswordResetEmailSent) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(SnackBar(
                content: Text(_T.emailSent(lang),
                    style: const TextStyle(fontFamily: 'Poppins', fontSize: 13)),
                backgroundColor: AppTheme.scoreGreen,
                behavior: SnackBarBehavior.floating,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                margin: const EdgeInsets.all(16),
              ));
            setState(() => _step2 = true);
          } else if (state is AuthPasswordResetSuccess) {
            ScaffoldMessenger.of(context)
              ..hideCurrentSnackBar()
              ..showSnackBar(SnackBar(
                content: Text(_T.success(lang),
                    style: const TextStyle(fontFamily: 'Poppins', fontSize: 13)),
                backgroundColor: AppTheme.scoreGreen,
                behavior: SnackBarBehavior.floating,
                shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
                margin: const EdgeInsets.all(16),
                duration: const Duration(seconds: 3),
              ));
            Future.delayed(const Duration(milliseconds: 600), () {
              if (mounted) Navigator.of(context).pop();
            });
          }
        },
        builder: (context, state) {
          final isLoading = state is AuthLoading;
          return SafeArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SizedBox(height: 12),
                  Icon(
                    _step2 ? Icons.lock_reset_outlined : Icons.mail_lock_outlined,
                    size: 56,
                    color: AppTheme.primary,
                  ),
                  const SizedBox(height: 16),
                  Text(
                    _step2 ? _T.step2Intro(lang) : _T.step1Intro(lang),
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontFamily: 'Poppins',
                      fontSize: 13,
                      height: 1.5,
                      color: isDark ? const Color(0xFFAABB99) : AppTheme.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 28),
                  if (!_step2)
                    _StepOne(
                      formKey: _formKey1,
                      emailController: _emailController,
                      lang: lang,
                      isDark: isDark,
                      isLoading: isLoading,
                      onSubmit: () => _submitStep1(lang),
                    )
                  else
                    _StepTwo(
                      formKey: _formKey2,
                      codeController: _codeController,
                      newPasswordController: _newPasswordController,
                      confirmController: _confirmController,
                      lang: lang,
                      isDark: isDark,
                      isLoading: isLoading,
                      onSubmit: () => _submitStep2(lang),
                      onResend: () => _submitStep1(lang),
                    ),
                  const SizedBox(height: 20),
                  Center(
                    child: TextButton(
                      onPressed: () => Navigator.of(context).pop(),
                      child: Text(
                        _T.backToLogin(lang),
                        style: TextStyle(
                          fontFamily: 'Poppins',
                          fontSize: 13,
                          color: titleColor,
                        ),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }
}

// ── Étape 1 : email ──────────────────────────────────────────────────────
class _StepOne extends StatelessWidget {
  final GlobalKey<FormState>  formKey;
  final TextEditingController emailController;
  final AppLanguage           lang;
  final bool                  isDark;
  final bool                  isLoading;
  final VoidCallback          onSubmit;

  const _StepOne({
    required this.formKey,
    required this.emailController,
    required this.lang,
    required this.isDark,
    required this.isLoading,
    required this.onSubmit,
  });

  @override
  Widget build(BuildContext context) {
    return Form(
      key: formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextFormField(
            controller: emailController,
            keyboardType: TextInputType.emailAddress,
            style: TextStyle(fontFamily: 'Poppins', color: isDark ? Colors.white : AppTheme.textPrimary),
            decoration: InputDecoration(
              labelText: _T.email(lang),
              prefixIcon: const Icon(Icons.email_outlined),
            ),
            validator: (v) => (v == null || v.trim().isEmpty || !v.contains('@'))
                ? _T.email(lang)
                : null,
          ),
          const SizedBox(height: 24),
          ElevatedButton(
            onPressed: isLoading ? null : onSubmit,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
              minimumSize: const Size(double.infinity, 52),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
            ),
            child: isLoading
                ? const SizedBox(
              width: 22, height: 22,
              child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
            )
                : Text(_T.sendCode(lang), style: const TextStyle(fontFamily: 'Poppins', fontWeight: FontWeight.w600)),
          ),
        ],
      ),
    );
  }
}

// ── Étape 2 : code + nouveau mot de passe ────────────────────────────────
class _StepTwo extends StatelessWidget {
  final GlobalKey<FormState>  formKey;
  final TextEditingController codeController;
  final TextEditingController newPasswordController;
  final TextEditingController confirmController;
  final AppLanguage           lang;
  final bool                  isDark;
  final bool                  isLoading;
  final VoidCallback          onSubmit;
  final VoidCallback          onResend;

  const _StepTwo({
    required this.formKey,
    required this.codeController,
    required this.newPasswordController,
    required this.confirmController,
    required this.lang,
    required this.isDark,
    required this.isLoading,
    required this.onSubmit,
    required this.onResend,
  });

  @override
  Widget build(BuildContext context) {
    final textColor = isDark ? Colors.white : AppTheme.textPrimary;
    return Form(
      key: formKey,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          TextFormField(
            controller: codeController,
            style: TextStyle(fontFamily: 'Poppins', color: textColor),
            decoration: InputDecoration(
              labelText: _T.code(lang),
              prefixIcon: const Icon(Icons.pin_outlined),
            ),
            validator: (v) => (v == null || v.trim().isEmpty) ? _T.code(lang) : null,
          ),
          const SizedBox(height: 16),
          TextFormField(
            controller: newPasswordController,
            obscureText: true,
            style: TextStyle(fontFamily: 'Poppins', color: textColor),
            decoration: InputDecoration(
              labelText: _T.newPassword(lang),
              helperText: _T.minLength(lang),
              prefixIcon: const Icon(Icons.lock_outline),
            ),
            validator: (v) => (v == null || v.length < 8) ? _T.minLength(lang) : null,
          ),
          const SizedBox(height: 16),
          TextFormField(
            controller: confirmController,
            obscureText: true,
            style: TextStyle(fontFamily: 'Poppins', color: textColor),
            decoration: InputDecoration(
              labelText: _T.confirmPassword(lang),
              prefixIcon: const Icon(Icons.lock_outline),
            ),
            validator: (v) => (v == null || v.isEmpty) ? _T.confirmPassword(lang) : null,
          ),
          const SizedBox(height: 24),
          ElevatedButton(
            onPressed: isLoading ? null : onSubmit,
            style: ElevatedButton.styleFrom(
              backgroundColor: AppTheme.primary,
              foregroundColor: Colors.white,
              minimumSize: const Size(double.infinity, 52),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
            ),
            child: isLoading
                ? const SizedBox(
              width: 22, height: 22,
              child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
            )
                : Text(_T.reset(lang), style: const TextStyle(fontFamily: 'Poppins', fontWeight: FontWeight.w600)),
          ),
          const SizedBox(height: 8),
          TextButton(
            onPressed: isLoading ? null : onResend,
            child: Text(
              _T.sendCode(lang),
              style: TextStyle(fontFamily: 'Poppins', fontSize: 12, color: AppTheme.primaryLight),
            ),
          ),
        ],
      ),
    );
  }
}