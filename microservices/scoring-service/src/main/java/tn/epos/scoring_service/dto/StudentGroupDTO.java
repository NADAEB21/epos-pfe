package tn.epos.scoring_service.dto;

import tn.epos.scoring_service.entities.StudentGroup;

public record StudentGroupDTO(
    Long id,
    Integer numeroGroupe,
    Object lot // Keep as Object or specific LotDTO
) {
    public static StudentGroupDTO fromEntity(StudentGroup group) {
        if (group == null) return null;
        return new StudentGroupDTO(
            group.getId(),
            group.getNumeroGroupe(),
            group.getLot()
        );
    }
}