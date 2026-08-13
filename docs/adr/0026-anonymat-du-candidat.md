# ADR-0026 — L'anonymat du candidat pendant la notation

**Statut : Proposé (différé) — décision de périmètre du 2026-08-14.**
**Décideuse : Nada (D4 du registre « Le reste du chantier », S39).**

## Contexte

Pendant la notation, l'évaluateur voit le **nom** de l'étudiant qu'il note, sur chaque écran
mobile (`grading_screen.dart`, `student_detail_screen.dart`). Dans un examen à enjeu,
l'anonymat du candidat est un dispositif d'équité classique : l'examinateur ne doit pas savoir
qu'il note l'étudiant avec qui il a eu un différend, ou l'enfant d'un collègue.

Le système possède déjà tout ce qu'il faut pour l'offrir : `Etudiant.numero_inscription`,
`numEchantillon`, les groupes. C'est aussi le pendant naturel de l'analyse de sévérité des
évaluateurs (UC-80, ADR-0021) : une comparaison de sévérité est plus probante si l'examinateur
ne savait pas qui il notait.

## Décision

1. **Le principe est retenu comme perspective, pas comme chantier v1.** Aucun code avant la
   soutenance (2026-09-01) : la fenêtre restante appartient au volet IA (D6 : gel du dev le
   22-23/08).
2. La cible, quand elle sera construite : un **mode « notation anonyme » par examen**, décidé
   par le responsable À LA CRÉATION de l'épreuve (pas débrayable en cours — sinon l'anonymat
   est un rideau qu'on soulève). L'évaluateur voit « Candidat n° 12 » ; le Suivi du responsable
   et les Résultats, eux, restent nominatifs (le responsable arrête des notes de personnes,
   pas de numéros).
3. UC-87 reste ❌ au catalogue, avec renvoi vers cet ADR — c'est une absence DÉCIDÉE, plus un
   angle mort.

## Conséquences

- La ligne « perspectives » du rapport peut citer ce dispositif comme travail arbitré.
- Toute demande de la faculté sur l'équité de notation a une réponse écrite : pensé, daté,
  planifié — pas oublié.
