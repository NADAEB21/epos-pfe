// lib/main.dart
// ================================================
// BF6.1 — Démarrage du WebSocketService (connexion après login via AuthBloc).
// BF6.2 — Démarrage du ConnectivityService (polling réseau) et du SyncService
//          (synchronisation automatique des notations hors-ligne).
//          L'OfflineStorageService (SQLite) s'initialise au premier accès.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:logger/logger.dart';

import 'app.dart';
import 'core/network/api_client.dart';
import 'core/offline/connectivity_service.dart';
import 'core/offline/offline_bloc.dart';
import 'core/offline/sync_service.dart';
import 'features/auth/data/repositories/auth_repository_impl.dart';
import 'features/auth/data/repositories/mock_auth_repository.dart';
import 'features/auth/domain/repositories/auth_repository.dart';
import 'features/grading/data/repositories/grading_repository_impl.dart';
import 'features/grading/data/repositories/mock_grading_repository.dart';
import 'features/grading/domain/repositories/grading_repository.dart';
import 'features/home/data/repositories/mock_session_repository.dart';
import 'features/home/data/repositories/session_repository_impl.dart';
import 'features/home/domain/repositories/session_repository.dart';

const bool _useMock = bool.fromEnvironment('USE_MOCK', defaultValue: false);

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);

  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor:          Colors.transparent,
      statusBarIconBrightness: Brightness.dark,
    ),
  );

  late final AuthRepository    authRepository;
  late final SessionRepository sessionRepository;
  late final GradingRepository gradingRepository;
  ApiClient? apiClient;

  if (_useMock) {
    debugPrint('⚙️  Mode MOCK — backend non requis');
    authRepository    = MockAuthRepository();
    sessionRepository = MockSessionRepository();
    gradingRepository = MockGradingRepository();
  } else {
    debugPrint('⚙️  Mode RÉEL — connexion Spring Boot');
    const storage = FlutterSecureStorage(
      aOptions: AndroidOptions(encryptedSharedPreferences: true),
    );
    final logger = Logger(
      printer: PrettyPrinter(methodCount: 0, errorMethodCount: 5),
    );
    apiClient         = ApiClient(storage: storage, logger: logger);
    authRepository    = AuthRepositoryImpl(apiClient: apiClient);
    sessionRepository = SessionRepositoryImpl(apiClient: apiClient);
    gradingRepository = GradingRepositoryImpl(apiClient: apiClient);

    // ── BF6.2 — Démarrage du service de connectivité ──────────────────────
    // Le ConnectivityService poll le backend toutes les 30 s (en ligne)
    // ou toutes les 5 s (hors-ligne) via HEAD /actuator/health.
    // Il est démarré ici, avant l'app, pour avoir un état réseau initial
    // disponible dès que les premiers BLoCs s'initialisent.
    ConnectivityService.instance.start();

    // ── BF6.2 — Initialisation du SyncService ─────────────────────────────
    // Le SyncService écoute ConnectivityService et déclenche automatiquement
    // la synchronisation des notations SQLite locales au retour en ligne.
    SyncService.instance.init(gradingRepository);

    debugPrint('⚙️  BF6 — ConnectivityService + SyncService démarrés');
  }

  runApp(
    EposApp(
      authRepository:    authRepository,
      sessionRepository: sessionRepository,
      gradingRepository: gradingRepository,
      apiClient:         apiClient,
    ),
  );
}