package tn.epos.scoring_service.dto;

/**
 * Summary returned by rotation generation. Counts let the frontend confirm
 * the circuit was built without re-querying. {@code avertissement} is null
 * unless a soft constraint was breached (e.g. group size exceeds the exam's
 * configured capacity per station).
 */
public record GenerationResult(
        int lots,
        int groupes,
        int stations,
        int creneaux,
        int rotations,
        int assignments,
        int etudiantsPresents,
        int etudiantsAbsents,
        String avertissement) {
}
