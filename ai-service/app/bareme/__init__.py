"""Étage C du module IA (#362 / N8) — le moteur de proposition.

- ``projection`` : le jumeau Python de ``BaremeDeliberationEngine`` (scoring)
  — l'arithmétique du barème de délibération, à l'identique, pour que
  l'effet PROJETÉ avant décision soit celui que ``/results`` servira après
  (ADR-0021 D10 : « jamais découvert après coup »).
- ``propositions`` : les déclencheurs (NOS seuils, documentés) qui
  transforment les indices N5 en propositions D8, et l'assemblage du payload.

Aucune écriture vers scoring, jamais : une proposition ne devient un barème
que par la main du responsable (ADR-0030 D1, ADR-0029 D2).
"""
