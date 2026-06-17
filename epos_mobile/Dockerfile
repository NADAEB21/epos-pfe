# Dockerfile — Build de l'APK Android en environnement isolé
# ================================================
# Utilisé par la pipeline CI/CD pour produire l'APK release.
# Pas destiné à être exécuté en local (utilise flutter run à la place).
#
# Usage :
#   docker build -t epos-mobile-builder .
#   docker run --rm -v $(pwd)/build:/app/build epos-mobile-builder

# === Stage 1 : Builder ===
FROM ghcr.io/cirruslabs/flutter:3.19.0 AS builder

# Variables
ARG API_BASE_URL=http://10.0.2.2:8080/api/v1
ENV FLUTTER_HOME=/usr/local/flutter
ENV PATH="$FLUTTER_HOME/bin:$PATH"

WORKDIR /app

# Copier uniquement pubspec en premier (cache Docker optimisé)
COPY pubspec.yaml pubspec.lock ./

# Installer les dépendances (couche cachée)
RUN flutter pub get

# Copier le reste du code source
COPY . .

# Générer le code (freezed, json_serializable)
RUN dart run build_runner build --delete-conflicting-outputs

# Build APK release
RUN flutter build apk \
    --release \
    --dart-define=API_BASE_URL=${API_BASE_URL} \
    --obfuscate \
    --split-debug-info=/app/build/symbols/

# === Stage 2 : Export minimal (juste l'APK) ===
FROM alpine:3.19 AS export

WORKDIR /output

COPY --from=builder /app/build/app/outputs/flutter-apk/app-release.apk \
                    /output/epos-mobile.apk

# L'APK est accessible dans /output/epos-mobile.apk
CMD ["sh", "-c", "echo 'APK prêt : /output/epos-mobile.apk' && ls -lh /output/"]