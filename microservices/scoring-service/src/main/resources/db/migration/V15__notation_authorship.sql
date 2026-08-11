-- #213 — QUI a réellement saisi la note, et QUI l'a verrouillée.
--
-- Jusqu'ici l'auteur n'était pas stocké : il était DÉDUIT de
-- rotation.evaluateur_id, c'est-à-dire du propriétaire de la station. Tant que
-- seul le propriétaire écrit, la déduction est juste. Dès qu'un autre écrit
-- (rien ne l'en empêche aujourd'hui), la base désigne la MAUVAISE personne —
-- reproduit en direct : note 9.5 saisie par l'évaluateur 6, attribuée au 3.
--
-- Une traçabilité fausse est pire qu'absente : en cas de réclamation, elle
-- accuse quelqu'un avec aplomb. On enregistre donc le fait plutôt que de le
-- déduire.
--
-- NULL = notation antérieure à cette migration : l'auteur est inconnu, et c'est
-- exactement ce qu'il faut dire. On ne le rétro-remplit PAS depuis la rotation,
-- ce serait recopier la déduction douteuse et la figer en fait.
--
-- FK logiques vers auth_db (autre base) : pas de contrainte SQL, même précédent
-- que rotation.evaluateur_id et station_evaluateurs.evaluateur_id.
ALTER TABLE notations ADD COLUMN saisi_par BIGINT;
ALTER TABLE notations ADD COLUMN verrouille_par BIGINT;

COMMENT ON COLUMN notations.saisi_par IS
    'Évaluateur ayant saisi/modifié la note en dernier (#213). NULL = inconnu (antérieur à V15).';
COMMENT ON COLUMN notations.verrouille_par IS
    'Évaluateur ayant verrouillé la notation (#213). NULL = jamais verrouillée, ou antérieur à V15.';
