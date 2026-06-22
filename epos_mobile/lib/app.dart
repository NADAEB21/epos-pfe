// lib/app.dart
// ================================================
// BF6.1 — getAccessToken est passé à AuthBloc pour initialiser le
//          WebSocketService immédiatement après chaque login réussi.
// BF6.2 — L'OfflineBloc est créé ici (singleton app-level) et injecté
//          dans le MultiBlocProvider global, ce qui le rend disponible
//          dans ConnectivityBanner (GradingScreen) et OfflinePendingBadge
//          sans aucun Provider supplémentaire dans les sous-arborescences.

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core/network/api_client.dart';
import 'core/offline/offline_bloc.dart';
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
  // BF6.2 — OfflineBloc au niveau app pour que ConnectivityBanner
  // et OfflinePendingBadge y accèdent sans re-création à chaque navigation.
  late final OfflineBloc _offlineBloc;

  bool _isAuthenticated  = false;
  bool _isInitialLoading = true;

  @override
  void initState() {
    super.initState();

    // BF6.2 — Créé avant AuthBloc pour être prêt dès le premier event.
    _offlineBloc = OfflineBloc();

    // BF6.1 — getAccessToken est transmis à AuthBloc pour qu'il puisse
    // initialiser le WebSocketService avec le JWT après chaque login.
    // En mode mock (apiClient == null) : la lambda retourne null, le
    // WebSocket n'est pas démarré, le reste de l'app fonctionne normalement.
    _authBloc = AuthBloc(
      authRepository: widget.authRepository,
      getAccessToken: widget.apiClient != null
          ? () => widget.apiClient!.getAccessToken()
          : null,
    )..add(const AuthCheckRequested());

    _sessionBloc = SessionBloc(repository: widget.sessionRepository);
    _profileBloc = ProfileBloc();

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
      }
      // AuthLoading / AuthInitial → pas de setState nécessaire.
    });
  }

  @override
  void dispose() {
    _authBloc.close();
    _sessionBloc.close();
    _profileBloc.close();
    _offlineBloc.close(); // BF6.2
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MultiBlocProvider(
      providers: [
        BlocProvider.value(value: _authBloc),
        BlocProvider.value(value: _sessionBloc),
        BlocProvider.value(value: _profileBloc),
        // BF6.2 — OfflineBloc disponible dans toute l'arborescence de widgets.
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
              child:         child!,
            ),
            home: _AuthGate(
              isInitialLoading:  _isInitialLoading,
              isAuthenticated:   _isAuthenticated,
              authBloc:          _authBloc,
              sessionBloc:       _sessionBloc,
              profileBloc:       _profileBloc,
              offlineBloc:       _offlineBloc, // BF6.2
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
  final bool              isInitialLoading;
  final bool              isAuthenticated;
  final AuthBloc          authBloc;
  final SessionBloc       sessionBloc;
  final ProfileBloc       profileBloc;
  final OfflineBloc       offlineBloc;       // BF6.2
  final GradingRepository gradingRepository;

  const _AuthGate({
    required this.isInitialLoading,
    required this.isAuthenticated,
    required this.authBloc,
    required this.sessionBloc,
    required this.profileBloc,
    required this.offlineBloc,
    required this.gradingRepository,
  });

  Widget _wrap(Widget child) => MultiBlocProvider(
    providers: [
      BlocProvider.value(value: authBloc),
      BlocProvider.value(value: sessionBloc),
      BlocProvider.value(value: profileBloc),
      // BF6.2 — Propagé dans chaque branche de navigation.
      BlocProvider.value(value: offlineBloc),
    ],
    child: child,
  );

  @override
  Widget build(BuildContext context) {
    if (isInitialLoading) return _wrap(const _SplashScreen());
    if (isAuthenticated)  return _wrap(HomeScreen(gradingRepository: gradingRepository));
    return _wrap(const LoginScreen());
  }
}

// ════════════════════════════════════════════════
// THÈME SOMBRE (inchangé)
// ════════════════════════════════════════════════
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
        (s) => s.contains(WidgetState.selected)
            ? AppTheme.primaryLight
            : Colors.grey,
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

// ════════════════════════════════════════════════
// SPLASH SCREEN (inchangé)
// ════════════════════════════════════════════════
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
              width:  90,
              height: 90,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white.withValues(alpha: 0.15),
              ),
              child: const Center(
                child: Text(
                  'EPOS',
                  style: TextStyle(
                    color:      Colors.white,
                    fontSize:   20,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 32),
            const SizedBox(
              width:  28,
              height: 28,
              child:  CircularProgressIndicator(
                strokeWidth: 2.5,
                valueColor:  AlwaysStoppedAnimation<Color>(Colors.white70),
              ),
            ),
          ],
        ),
      ),
    );
  }
}