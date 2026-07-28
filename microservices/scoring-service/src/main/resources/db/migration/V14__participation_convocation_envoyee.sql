-- #227 — quand la convocation de cet étudiant a été envoyée par e-mail.
--
-- Sans cette trace, l'écran ne peut ni dire « déjà envoyées le ... » ni prévenir
-- avant un second envoi : le responsable qui reclique spamme la promotion sans
-- le savoir, et personne ne peut savoir qui a réellement été convoqué.
--
-- Sur la participation (et non sur l'étudiant) : un étudiant passe plusieurs
-- examens, et c'est LA convocation à CET examen qui a été envoyée.
-- NULL = jamais envoyée.
ALTER TABLE examen_participations ADD COLUMN convocation_envoyee_a TIMESTAMP;
