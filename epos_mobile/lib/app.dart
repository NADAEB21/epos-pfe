import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core/network/api_client.dart';
import 'core/theme/app_theme.dart';
import 'features/auth/domain/repositories/auth_repository.dart';
import 'features/auth/presentation/bloc/auth_bloc.dart';
import 'features/auth/presentation/screens/login_screen.dart';
import 'features/grading/domain/repositories/grading_repository.dart';
import 'features/home/domain/repositories/session_repository.dart';
import 'features/home/presentation/bloc/session_bloc.dart';
import 'features/home/presentation/screens/home_screen.dart';
import 'features/profile/domain/entities/profile_settings.dart';
import 'features/profile/presentation/bloc/profile_bloc.dart';
import 'core/offline/connectivity_service.dart';
import 'core/offline/offline_bloc.dart';
import 'core/offline/sync_service.dart';

class EposApp extends StatefulWidget {
  final AuthRepository    authRepository;
  final SessionRepository sessionRepository;
  final GradingRepository gradingRepository;
  final ApiClient?        apiClient;

  const EposApp({
    super.key,
    required this.authRepository,
    required this.sessionRepository,
    required this.gradingRepository,
    this.apiClient,
  });

  @override
  State<EposApp> createState() => _EposAppState();
}

class _EposAppState extends State<EposApp> {
  late final AuthBloc    _authBloc;
  late final SessionBloc _sessionBloc;
  late final ProfileBloc _profileBloc;
  late final OfflineBloc _offlineBloc;

  bool _isAuthenticated  = false;
  // _isInitialLoading : true uniquement pendant la vérification du token au
  // démarrage.  Dès que la vérification est terminée (quel que soit le résultat),
  // il passe définitivement à false.
  // Pendant une tentative de login, il reste false → LoginScreen reste monté →
  // son BlocListener reçoit AuthFailure → la snackbar d'erreur s'affiche.
  bool _isInitialLoading = true;

  @override
  void initState() {
    super.initState();
    _authBloc    = AuthBloc(authRepository: widget.authRepository)
      ..add(const AuthCheckRequested());
    _sessionBloc = SessionBloc(repository: widget.sessionRepository);
    // BF1.3 / changement de mot de passe (écran Profil) — ProfileBloc a
    // besoin de l'AuthRepository pour appeler le vrai endpoint
    // PUT /auth/change-password au lieu de l'ancienne validation mock.
    _profileBloc = ProfileBloc(authRepository: widget.authRepository);
    _offlineBloc = OfflineBloc();

    _authBloc.stream.listen((state) {
      if (!mounted) return;

      if (state is AuthAuthenticated) {
        _sessionBloc.add(const SessionLoadRequested());
        setState(() {
          _isAuthenticated  = true;
          _isInitialLoading = false;
        });

      } else if (state is AuthUnauthenticated || state is AuthFailure) {
        setState(() {
          _isAuthenticated  = false;
          _isInitialLoading = false;
        });

        // Vide la pile du navigator racine jusqu'à la route initiale, pour que
        // le changement de `home` (HomeScreen → LoginScreen déclenché par le
        // setState ci-dessus) soit visible immédiatement, sans clic retour.
        // Sans ça, un écran empilé par-dessus (ex: ProfileScreen, ouvert via
        // Navigator.of(context, rootNavigator: true).push) reste affiché : le
        // compte est bien déconnecté "en dessous", mais rien ne le montre.
        _appNavigatorKey.currentState?.popUntil((route) => route.isFirst);

      }
      // AuthLoading / AuthInitial / AuthPasswordReset* → pas de setState :
      //   • Vérification initiale : _isInitialLoading est encore true →
      //     SplashScreen déjà affiché, pas de rebuild utile.
      //   • Tentative de login ou flux "mot de passe oublié" :
      //     _isInitialLoading est déjà false → LoginScreen /
      //     ForgotPasswordScreen restent montés avec leur propre
      //     BlocConsumer/BlocListener local.
    });
  }

  @override
  void dispose() {
    _authBloc.close();
    _sessionBloc.close();
    _profileBloc.close();
    _offlineBloc.close();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiBlocProvider(
      providers: [
        BlocProvider.value(value: _authBloc),
        BlocProvider.value(value: _sessionBloc),
        BlocProvider.value(value: _profileBloc),
        BlocProvider.value(value: _offlineBloc),
      ],
      child: BlocBuilder<ProfileBloc, ProfileState>(
        bloc: _profileBloc,
        builder: (context, profileState) {
          final isDark   = profileState.settings.themeMode == AppThemeMode.dark;
          final locale   = profileState.settings.language.locale;
          final isArabic = profileState.settings.language == AppLanguage.arabic;

          return MaterialApp(
            title:                      'EPOS Mobile',
            debugShowCheckedModeBanner: false,
            navigatorKey:               _appNavigatorKey,
            theme:     AppTheme.lightTheme,
            darkTheme: buildDarkTheme(),
            themeMode: isDark ? ThemeMode.dark : ThemeMode.light,
            locale:    locale,
            localizationsDelegates: const [
              GlobalMaterialLocalizations.delegate,
              GlobalWidgetsLocalizations.delegate,
              GlobalCupertinoLocalizations.delegate,
            ],
            supportedLocales: const [
              Locale('fr'),
              Locale('en'),
              Locale('ar'),
            ],
            builder: (ctx, child) => Directionality(
              textDirection: isArabic ? TextDirection.rtl : TextDirection.ltr,
              child: child!,
            ),
            home: _AuthGate(
              isInitialLoading:  _isInitialLoading,  // ← renommé
              isAuthenticated:   _isAuthenticated,
              authBloc:          _authBloc,
              sessionBloc:       _sessionBloc,
              profileBloc:       _profileBloc,
              gradingRepository: widget.gradingRepository,
            ),
          );
        },
      ),
    );
  }
}

final GlobalKey<NavigatorState> _appNavigatorKey = GlobalKey<NavigatorState>();

class _AuthGate extends StatelessWidget {
  final bool              isInitialLoading;   // ← renommé
  final bool              isAuthenticated;
  final AuthBloc          authBloc;
  final SessionBloc       sessionBloc;
  final ProfileBloc       profileBloc;
  final GradingRepository gradingRepository;

  const _AuthGate({
    required this.isInitialLoading,
    required this.isAuthenticated,
    required this.authBloc,
    required this.sessionBloc,
    required this.profileBloc,
    required this.gradingRepository,
  });

  Widget _wrap(Widget child) => MultiBlocProvider(
    providers: [
      BlocProvider.value(value: authBloc),
      BlocProvider.value(value: sessionBloc),
      BlocProvider.value(value: profileBloc),
    ],
    child: child,
  );

  @override
  Widget build(BuildContext context) {
    // SplashScreen uniquement pendant la vérification du token au démarrage
    if (isInitialLoading) return _wrap(const _SplashScreen());
    if (isAuthenticated)  return _wrap(HomeScreen(gradingRepository: gradingRepository));
    // LoginScreen reste monté pendant les tentatives de login →
    // son BlocListener peut désormais recevoir AuthFailure et afficher la snackbar
    return _wrap(const LoginScreen());
  }
}

// ... (buildDarkTheme et _SplashScreen inchangés)
ThemeData buildDarkTheme() {
  return ThemeData(
    useMaterial3:            true,
    brightness:              Brightness.dark,
    scaffoldBackgroundColor: const Color(0xFF1A1F14),
    colorScheme: ColorScheme.fromSeed(
      seedColor:  AppTheme.primary,
      brightness: Brightness.dark,
      primary:    AppTheme.primaryLight,
      surface:    const Color(0xFF252B1E),
    ),
    cardColor:             const Color(0xFF252B1E),
    dialogBackgroundColor: const Color(0xFF2C3322),
    appBarTheme: const AppBarTheme(
      backgroundColor: Color(0xFF1A2610),
      foregroundColor: Colors.white,
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled:    true,
      fillColor: const Color(0xFF2C3322),
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide:   const BorderSide(color: Color(0xFF3D4A30)),
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide:   const BorderSide(color: Color(0xFF3D4A30)),
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(10),
        borderSide:   const BorderSide(color: AppTheme.primaryLight, width: 2),
      ),
      labelStyle: const TextStyle(color: Color(0xFF9E9E9E)),
    ),
    switchTheme: SwitchThemeData(
      thumbColor: WidgetStateProperty.resolveWith(
            (s) => s.contains(WidgetState.selected) ? AppTheme.primaryLight : Colors.grey,
      ),
      trackColor: WidgetStateProperty.resolveWith(
            (s) => s.contains(WidgetState.selected)
            ? AppTheme.primaryLight.withValues(alpha: 0.4)
            : Colors.grey.withValues(alpha: 0.3),
      ),
    ),
    textTheme: const TextTheme(
      bodyLarge:   TextStyle(color: Colors.white),
      bodyMedium:  TextStyle(color: Color(0xFFCCCCCC)),
      titleLarge:  TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
      titleMedium: TextStyle(color: Colors.white),
    ),
    dividerColor: const Color(0xFF3D4A30),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: AppTheme.primaryLight,
        foregroundColor: Colors.white,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      ),
    ),
    bottomNavigationBarTheme: const BottomNavigationBarThemeData(
      backgroundColor:     Color(0xFF252B1E),
      selectedItemColor:   AppTheme.primaryLight,
      unselectedItemColor: Color(0xFF9E9E9E),
    ),
  );
}

class _SplashScreen extends StatelessWidget {
  const _SplashScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppTheme.primaryDark,
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Container(
              width: 90, height: 90,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white.withValues(alpha: 0.15),
              ),
              child: const Center(
                child: Text(
                  'EPOS',
                  style: TextStyle(
                    color: Colors.white, fontSize: 20, fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 32),
            const SizedBox(
              width: 28, height: 28,
              child: CircularProgressIndicator(
                strokeWidth: 2.5,
                valueColor: AlwaysStoppedAnimation<Color>(Colors.white70),
              ),
            ),
          ],
        ),
      ),
    );
  }
}