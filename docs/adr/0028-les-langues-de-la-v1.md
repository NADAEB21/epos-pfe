# ADR-0028 — Les langues de la v1 : mobile trilingue, web français

**Statut : Accepté — 2026-08-14.**
**Décideuse : Nada (D2 du registre « Le reste du chantier », S39).**

## Contexte

L'inventaire S39 a mesuré l'état réel :

- **Mobile** : un sélecteur FR/EN/AR existe et fonctionne (login, accueil, profil, RTL global),
  mais les **deux écrans les plus utilisés — la notation et le détail étudiant — sont 100 %
  français codé en dur**, via quatre cartes de traduction dupliquées à la main. Basculer en
  arabe donne une coquille RTL traduite autour d'une grille française : une demi-traduction
  qui dessert l'évaluateur plus qu'un français constant.
- **Web** : aucune plomberie i18n (pas de bibliothèque, pas de catalogue de chaînes, tout en
  littéraux français), aucun `LOCALE_ID` (les dates se rendaient en-US), pas de miroir RTL.
  Rendre le web trilingue ≈ 3-4 jours (extraction de ~20 écrans denses + RTL Tailwind).

La soutenance est le 2026-09-01 et le volet IA n'est pas commencé (gel du dev : 22-23/08).

## Décision

1. **Le mobile devient réellement trilingue (FR/EN/AR)** — chantier W21 : traduire la notation
   et le détail étudiant, fusionner les quatre cartes en une seule source, **persister le
   choix de langue** (aujourd'hui il se perd à chaque lancement — W4). L'évaluateur est
   l'acteur le plus divers (assistants, praticiens invités) : c'est là que les langues rendent
   un service réel.
2. **Le web reste français en v1.** Ses utilisateurs (responsables, administration) travaillent
   dans une interface institutionnelle française ; le coût du trilingue web se paierait
   directement sur la semaine IA. Seul correctif immédiat : `LOCALE_ID = fr` (W5), pour que
   dates et nombres cessent de se rendre en anglais dans une interface française.
3. **Le trilingue web est une perspective écrite**, pas un oubli : si le produit dépasse la
   faculté pilote, l'extraction des chaînes se fera avec l'outillage Angular standard.

## Conséquences

- Le sélecteur mobile ne proposera une langue que si elle est COMPLÈTE à l'écran de notation —
  la règle qui interdit le retour de la demi-traduction.
- Le rapport peut décrire la politique linguistique comme une décision de périmètre datée.
