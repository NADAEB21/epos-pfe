// lib/core/utils/app_translations.dart

import '../../features/profile/domain/entities/profile_settings.dart';

class Tr {
  Tr._();

  static const Map<String, Map<String, String>> _data = {
    'login_title':           {'fr': 'EPOS', 'en': 'EPOS', 'ar': 'EPOS'},
    'login_subtitle':        {'fr': 'Evaluation Platform for Operational Skills', 'en': 'Evaluation Platform for Operational Skills', 'ar': 'منصة تقييم المهارات العملية'},
    'login_username':        {'fr': "Nom d'utilisateur", 'en': 'Username', 'ar': 'اسم المستخدم'},
    'login_password':        {'fr': 'Mot de passe', 'en': 'Password', 'ar': 'كلمة المرور'},
    'login_forgot':          {'fr': 'Mot de passe oublié ?', 'en': 'Forgot password?', 'ar': 'نسيت كلمة المرور؟'},
    'login_btn':             {'fr': 'Se connecter', 'en': 'Sign in', 'ar': 'تسجيل الدخول'},
    'login_footer':          {'fr': 'Faculté de Pharmacie de Monastir', 'en': 'Faculty of Pharmacy of Monastir', 'ar': 'كلية الصيدلة بالمنستير'},
    'login_err_empty':       {'fr': "Veuillez saisir votre nom d'utilisateur.", 'en': 'Please enter your username.', 'ar': 'يرجى إدخال اسم المستخدم.'},
    'login_err_pwd':         {'fr': 'Veuillez saisir votre mot de passe.', 'en': 'Please enter your password.', 'ar': 'يرجى إدخال كلمة المرور.'},
    'login_err_short':       {'fr': 'Min. 8 caractères.', 'en': 'Min. 8 characters.', 'ar': '8 أحرف على الأقل.'},
    'home_bonjour':          {'fr': 'Bonjour,', 'en': 'Hello,', 'ar': 'مرحباً،'},
    'home_dr':               {'fr': 'Dr', 'en': 'Dr', 'ar': 'د.'},
    'home_en_cours':         {'fr': 'EN COURS', 'en': 'ONGOING', 'ar': 'جارية'},
    'home_reprendre':        {'fr': 'Reprendre la notation', 'en': 'Resume grading', 'ar': 'استئناف التقييم'},
    'home_a_venir':          {'fr': 'Sessions à venir', 'en': 'Upcoming', 'ar': 'الجلسات القادمة'},
    'home_planning':         {'fr': 'Planning du jour', 'en': "Today's schedule", 'ar': 'جدول اليوم'},
    'home_no_session':       {'fr': 'Aucune session en cours', 'en': 'No ongoing session', 'ar': 'لا توجد جلسة جارية'},
    'home_no_session_msg':   {'fr': "Vous n'avez pas de session active.\nConsultez le planning ci-dessous.", 'en': 'You have no active session.\nCheck the schedule below.', 'ar': 'ليس لديك جلسة نشطة.\nراجع الجدول أدناه.'},
    'home_no_session_snack': {'fr': 'Aucune session en cours pour le moment.', 'en': 'No session in progress.', 'ar': 'لا توجد جلسة جارية حالياً.'},
    'stat_sessions':         {'fr': 'sessions\nassignées', 'en': 'assigned\nsessions', 'ar': 'جلسات\nمخصصة'},
    'stat_etudiants':        {'fr': 'Étudiants', 'en': 'Students', 'ar': 'الطلاب'},
    'stat_lots':             {'fr': 'Lots\nvalidés', 'en': 'Validated\ngroups', 'ar': 'مجموعات\nمُعتمدة'},
    'nav_accueil':           {'fr': 'Accueil', 'en': 'Home', 'ar': 'الرئيسية'},
    'nav_notation':          {'fr': 'Notation', 'en': 'Grading', 'ar': 'التقييم'},
    'nav_profil':            {'fr': 'Profil', 'en': 'Profile', 'ar': 'الملف'},
    'legend_a_venir':        {'fr': '» à venir', 'en': '» upcoming', 'ar': '» قادم'},
    'legend_termine':        {'fr': '✓ terminé', 'en': '✓ done', 'ar': '✓ تمّ'},
    'legend_aucun':          {'fr': '— aucun', 'en': '— none', 'ar': '— لا شيء'},
    'legend_heure':          {'fr': 'Heure', 'en': 'Time', 'ar': 'الساعة'},
  };

  static String get(AppLanguage lang, String key) =>
      _data[key]?[lang.code] ?? _data[key]?['fr'] ?? key;
}