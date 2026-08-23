# Spec des composants de graphes (F2 / #356) — issue de N4 (#355)

**Produit par N4 (écran de délibération, PR liée à #355), consommé par F2 (#356, Feten)
puis N10 (#365, BI).** Réf. : ADR-0029 D5 (Angular + ngx-echarts, jamais d'outil BI
externe), ADR-0021 D2 (contrat de refus), ADR-0021 D4 (l'écran de délibération ne dépend
JAMAIS du module IA).

## Le principe

L'écran de délibération (`resultats.component`) rend aujourd'hui ses distributions en
**barres Tailwind** (`[style.width.%]`, idiome maison — zéro dépendance, l'écran survit à
tout). F2 livre les composants ngx-echarts qui les remplacent **à contrat identique** :
mêmes entrées, mêmes événements, mêmes états vides. Le remplacement est un échange de
balise, pas une refonte — et si ngx-echarts pose problème, les barres Tailwind restent le
repli permanent.

## Composant 1 — `HistogrammeStation`

La distribution des notes verrouillées d'une station (délibération) ou d'un agrégat (BI).

**Entrées** (le type source de vérité est `DeliberationBin` dans
`frontend-web/src/app/features/examens/resultats/resultats.component.ts`) :

| Entrée | Type | Sens |
|---|---|---|
| `bins` | `{ label: string; count: number; pct: number; sousSeuil: boolean }[]` | Bacs déjà calculés — le composant NE recalcule jamais, il rend. |
| `n` | `number` | Effectif (les notes verrouillées). Toujours affiché à côté du graphe. |
| `seuilLabel` | `string` | Libellé du seuil, affiché tel quel (ex. « échec = note < 50 % du barème »). |

**Événements** : `binClick(label: string)` — la délibération s'en sert pour filtrer/ouvrir.

**États obligatoires** :
- `n === 0` → le composant rend l'état vide « Aucune notation verrouillée — pas de
  distribution à lire. » **Jamais un graphe vide ni des zéros silencieux.**
- Entrée absente/malformée → rien du tout (le composant se dégrade à néant, il ne
  fabrique JAMAIS de données — leçon du 403 avalé, ADR-0029 D7).

## Composant 2 — `BarresConcentration`

Le classement des stations par taux d'échec (la « concentration » de D4), et demain tout
classement à barres horizontales du BI.

**Entrées** :

| Entrée | Type | Sens |
|---|---|---|
| `lignes` | `{ id: number; label: string; valeurPct: number \| null; n: number; detail?: string }[]` | Une ligne par station. `valeurPct: null` = rien à lire (voir états). |
| `titreValeur` | `string` | Ce que la barre mesure (ex. « Taux d'échec »). |

**Événements** : `ligneClick(id: number)`.

**États obligatoires** :
- `valeurPct === null` → la ligne s'affiche SANS barre, avec la mention « aucune
  notation verrouillée » — la station muette reste visible, jamais masquée.
- `n` petit : la valeur s'affiche TOUJOURS accompagnée de son n (« 50 % (sur 2) »).
  Pour les indices psychométriques (N5+), le contrat de refus d'ADR-0021 D2 s'applique
  en AMONT (le backend envoie le refus, pas une valeur) — le composant rend alors le
  texte de refus verbatim : « comparaison non concluante — effectif insuffisant ».

## Règles transverses (les deux composants)

1. **Palette** : jetons Tailwind du projet uniquement — `brand` `#1f5e3a` pour le
   nominal, `red-400` pour le sous-seuil, `gray-100` pour les fonds de barre,
   `amber-*` pour les états dégradés. Pas de palette ECharts par défaut.
2. **Nombres** : `tabular-nums`, 1 décimale max, jamais de pourcentage sans son n.
3. **Accessibilité** : conteneur `role="img"` + `aria-label` décrivant la mesure
   (voir l'existant dans `resultats.component.html`, section délibération).
4. **Aucun appel réseau** : composants de RENDU pur. Les données arrivent par input ;
   qui les calcule (scoring pour la délibération, ai-service pour les indices) est le
   problème de l'écran hôte, jamais du composant.
5. **SSR/perf poste facultaire** : init ECharts en `lazy` (le chunk ngx-echarts ne doit
   pas alourdir le bundle initial — vérifier avec `ng build --stats-json`).
6. **Specs** : chaque composant arrive avec ses specs Jasmine (état vide, état refus,
   émission des événements). Rappel : la CI frontend ne lance QUE `npm run build` —
   exécuter `ng test` en local avant la PR.

## Où brancher (l'échange de balise)

- `resultats.component.html`, section « Délibération par station » : le bloc de barres
  (`@for (bin of d.bins ...)`) devient `<app-histogramme-station [bins]="d.bins" ...>`,
  et le tri des cartes alimente `BarresConcentration` si on préfère une vue compacte.
- Les données sont déjà calculées par `deliberation()` (computed) — F2 ne touche NI au
  calcul NI aux appels réseau.
