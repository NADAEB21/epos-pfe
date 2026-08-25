# Documentation technique : Composants de graphes (F2)

Cette documentation décrit comment installer et tester les nouveaux composants de visualisation basés sur ECharts dans l'environnement de développement.

## 1. Installation des dependances
`echarts` et `ngx-echarts@18` (la version compatible Angular 18) sont deja dans
`package.json` + `package-lock.json` — l'installation normale suffit, ne pas
faire de `npm install <paquet>` ad hoc (le lockfile est la reference) :

```bash
cd frontend-web
npm ci
```

## 2. Lancement
Lancez le frontend normalement :
```bash
npm start
```

## 3. Verification isolee (Sandbox)
Un harnais de test a ete cree pour valider les composants sans avoir besoin de manipuler de vrais examens ou le backend.
1. Accedez a l'URL : `http://localhost:4200/dev/graphes` (hors shell — aucune
   connexion necessaire ; la route est gardee par `isDevMode()` et repond 404
   en build de production).
2. Vous pourrez tester :
  - Le rendu nominal des histogrammes et barres.
  - La bascule du mode clair au mode sombre.
  - Le comportement en cas de donnees absentes (n=0) ou malformees.

## 4. Utilisation des composants
Les composants sont situes dans `src/app/shared/graphes/`. Ils sont autonomes (Standalone).

### Histogramme de station
Affiche la distribution des notes avec un code couleur pour le seuil d'echec.
```html
<app-histogramme-station
  [bins]="mesDonnees"
  [n]="12"
  seuilLabel="echec < 10/20"
  [theme]="'light' | 'dark'"
/>
```

### Barres de concentration
Affiche un classement horizontal (ex: taux d'echec par station). Gere les messages de refus du backend si l'effectif est insuffisant.
```html
<app-barres-concentration
  [lignes]="mesLignes"
  titreValeur="Taux d'echec"
  [theme]="'light' | 'dark'"
/>
```

## 5. Notes pour l'integration (N5/N6)
- Les composants n'effectuent aucun calcul. Ils attendent des donnees deja transformees (bins ou lignes).
- Si vous ajoutez ces composants dans un autre module, n'oubliez pas de les ajouter dans le tableau `imports: [...]` de votre composant parent.
- Les couleurs (Vert brand, Rouge 400) sont figees pour respecter la charte graphique du projet.
- **Chargement LAZY (spec N4, regle 5)** : ECharts est fourni par une fabrique
  dynamique (`app.config.ts` → `echarts-setup.loadEcharts`) — le chunk ECharts
  n'est charge qu'au premier graphe affiche. Ne JAMAIS reintroduire un
  `import * as echarts from 'echarts/core'` statique dans `app.config.ts` ou un
  composant : cela remettrait ~340 kB dans le bundle initial de toutes les
  routes (mesure : 782.84 kB → 375.70 kB en corrigeant ce point).
