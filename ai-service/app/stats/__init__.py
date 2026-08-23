"""Le moteur statistique (#357, N5) — pur calcul, aucune I/O ici.

Sous-modules :
- ``types``     : les formes de données (entrées + le résultat à contrat de refus) ;
- ``engine``    : les quatre indices d'ADR-0008/0021, écrits à la main ;
- ``bootstrap`` : l'IC percentile, graine fixée (reproductibilité ADR-0029 D3) ;
- ``loader``    : vues ``v_ai_*`` → entrées du moteur (croisement inter-bases en Python) ;
- ``runner``    : l'orchestration par examen — la forme que #359 mettra en cache.
"""
