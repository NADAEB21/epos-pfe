# EPOS — fiche du poste serveur (à afficher près du PC)

**Adresse de l'application :** `https://epos.<tailnet>.ts.net`
(web pour les responsables et l'administration ; l'application Android des
évaluateurs pointe déjà sur cette adresse).

**Compte administrateur :** `eposfphm@gmail.com` (mot de passe choisi par la
faculté via le courriel d'invitation ; « Mot de passe oublié » sur l'écran de
connexion en cas de perte).

## Ce PC EST le serveur

- **Ne jamais l'éteindre, le mettre en veille, ni fermer la session.** Si le PC
  est éteint, EPOS est hors ligne pour tout le monde.
- Il redémarre seul après une coupure : la session s'ouvre automatiquement,
  Docker et EPOS repartent en ~5 minutes.
- **Pas de mise à jour Windows ni de redémarrage pendant un examen.**
  Les faire un jour sans examen.
- Ne pas désinstaller Docker Desktop ni Tailscale, ne pas modifier
  `C:\EPOS\epos.env`.

## Sauvegardes

- Automatique chaque soir à 23:00 dans `C:\EPOS\backups\` (30 jours conservés).
- **Après chaque journée d'examen : copier le dernier dossier sur une clé USB
  chiffrée** rangée hors de la salle. Une sauvegarde restée sur ce disque ne
  protège pas d'une panne de disque.
- Garder aussi une copie de `C:\EPOS\epos.env` avec les sauvegardes (sans lui,
  une restauration sur un autre PC est impossible). Ces fichiers contiennent
  des données personnelles : jamais sur un partage ouvert.

## Si l'application ne répond pas

1. Le PC est-il allumé, la session ouverte, Internet disponible ?
2. Ouvrir `http://localhost` sur le PC lui-même :
   - la page s'affiche → problème de tunnel : clic droit sur l'icône Tailscale
     → vérifier « Connected » ; sinon redémarrer le PC ;
   - la page ne s'affiche pas → double-cliquer l'icône Docker, attendre qu'il
     soit « running », puis redémarrer le PC si besoin.
3. Journaux : `C:\EPOS\logs`.

Procédure technique complète : `C:\EPOS\app\infrastructure\README-windows-server.md`.
