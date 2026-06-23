# EPOS Mobile 📱

**Evaluation Platform for Operational Skills** — Application mobile Flutter pour la digitalisation des examens pratiques EPOS en Chimie Thérapeutique à la Faculté de Pharmacie de Monastir.

---

## Aperçu

L'application permet aux évaluateurs (enseignants) de noter simultanément 4 étudiants par station, avec calcul automatique du score en temps réel, mode hors-ligne, et synchronisation avec le backend Spring Boot.

**Stack :** Flutter 3.x · Dart · BLoC · Dio · SQLite · WebSocket (STOMP)

---

## Démarrage rapide

### 1. Lancer l'infrastructure 

```bash
cd infrastructure    # Dossier du backend
docker-compose up -d --build
# Vérification : http://localhost:5050 (pgAdmin)
```

### 2. Installer les dépendances Flutter

```bash
flutter pub get
```

### 3. Lancer l'application

```bash
# Sur émulateur Android (s'assurer qu'il est démarré dans Android Studio)
flutter run

# Ou sur appareil physique connecté via USB
flutter devices
flutter run --device-id <device_id>

# Sur Chrome (Recommendé)
flutter run -d chrome --web-port 4200
```

> **Note :** Le backend Spring Boot doit tourner sur `localhost:8080`. Depuis l'émulateur Android, l'alias est automatiquement `10.0.2.2`.

---

## Structure du projet

```
lib/
├── core/           # Config réseau, thème, utilitaires
├── features/
│   ├── auth/       # Connexion JWT
│   ├── home/       # Dashboard évaluateur
│   ├── grading/    # Interface de notation (feature principale)
│   └── profile/    # Profil & paramètres
└── shared/         # Widgets partagés
```

---

## Contribuer

1. Créer une branche depuis `develop` : `git checkout -b feature/ma-feature`
2. Coder + tester localement
3. Ouvrir une Pull Request vers `develop`
4. La pipeline CI doit passer avant le merge