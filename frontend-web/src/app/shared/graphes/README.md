# Documentation technique : Composants de graphes (F2)

Cette documentation décrit comment installer et tester les nouveaux composants de visualisation basés sur ECharts dans l'environnement de développement.

## 1. Installation des dependances
Pour eviter les conflits de version avec Angular 18, il est important d'installer la version specifique du wrapper.

```bash
cd frontend-web
npm install echarts ngx-echarts@18
```

## 2. Lancement
Lancez le frontend normalement :
```bash
npm start
```

## 3. Verification isolee (Sandbox)
Un harnais de test a ete cree pour valider les composants sans avoir besoin de manipuler de vrais examens ou le backend.
1. Connectez-vous a l'application.
2. Accedez manuellement a l'URL : `http://localhost:4200/dev/graphes`
3. Vous pourrez tester :
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
