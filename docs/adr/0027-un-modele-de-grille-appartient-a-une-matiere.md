# ADR-0027 — Un modèle de grille appartient à une matière

**Statut : Accepté (mise en œuvre différée après le volet IA) — 2026-08-14.**
**Décideuse : Nada (D5 du registre « Le reste du chantier », S39).**

## Contexte

La bibliothèque de modèles de grilles est aujourd'hui **un seul sac partagé par toute la
faculté** : `GrilleTemplate` ne porte ni `matiere_id`, ni auteur, ni portée (constat S36,
vérifié en source). Trois conséquences :

1. **Personne ne peut faire le ménage.** `DELETE /templates/grilles/{id}` est réservé
   `SUPER_ADMIN` — que ADR-0018 D5 exclut précisément de l'autorité pédagogique — et l'écran
   du responsable n'affiche donc aucun bouton de suppression. Un mauvais modèle enregistré est
   éternel : la bibliothèque ne peut que grossir.
2. **Le sac n'est pas rangé.** Un modèle de Chimie thérapeutique est proposé par défaut à la
   Toxicologie. À 5 matières ça se supporte ; à l'échelle d'une faculté, la bibliothèque
   devient illisible.
3. La création *ex nihilo* d'un modèle est elle aussi `SUPER_ADMIN` seul — le verbe d'auteur
   donné à celui qui ne doit pas l'avoir, refusé à celui dont c'est le métier. L'écran
   « Templates globaux » côté admin a été **supprimé** pour cette raison (W2, S39 —
   recommandation S36 §2 confirmée par Nada le 2026-08-13).

## Décision

1. **Un modèle de grille appartient à une matière**, et porte son **auteur**
   (`grille_templates.matiere_id` + `created_by` — migration exam-service).
2. La bibliothèque du responsable s'ouvre **filtrée sur SA matière**, avec une bascule
   explicite « toutes les matières » (emprunter une bonne idée reste légitime).
3. **La suppression revient au propriétaire** : les responsables de la matière du modèle.
   L'administrateur conserve la suppression comme recours de supervision (acte motivé, pas
   quotidien) — cohérent avec ADR-0018 D3/D5.
4. La création *ex nihilo* et l'enregistrement depuis une grille suivent la même règle :
   actes de responsable, dans sa matière.

## Mise en œuvre

**Différée après le volet IA** (gel du dev 22-23/08). D'ici là : aucun écran admin de
templates ; la bibliothèque responsable actuelle (globale, sans suppression) reste l'état
assumé, documenté par cet ADR.

## Conséquences

- L'écran « Ma matière » supprimé en W2 pourra renaître un jour avec un vrai contenu
  (les modèles de MA matière) — c'est la seule résurrection prévue.
- La migration devra adopter les modèles existants : `matiere_id` inconnu → NULL assumé
  (« hérité, visible partout ») plutôt qu'une attribution inventée.
