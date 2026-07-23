// lib/main.dart

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'core/network/token_store.dart';
import 'package:logger/logger.dart';

import 'app.dart';
import 'core/network/api_client.dart';
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
    // TokenStore : repli mémoire quand le stockage sécurisé est indisponible
    // (web servi en http://<ip> — WebCrypto absent hors contexte sécurisé).
    final storage = TokenStore(const FlutterSecureStorage(
      aOptions: AndroidOptions(encryptedSharedPreferences: true),
    ));
    final logger = Logger(
      printer: PrettyPrinter(methodCount: 0, errorMethodCount: 5),
    );
    apiClient         = ApiClient(storage: storage, logger: logger);
    authRepository    = AuthRepositoryImpl(apiClient: apiClient);
    sessionRepository = SessionRepositoryImpl(apiClient: apiClient);
    gradingRepository = GradingRepositoryImpl(apiClient: apiClient);
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