package tn.epos.auth_service.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tn.epos.common.dto.ApiResponse;
import tn.epos.auth_service.dto.MatiereResponse;
import tn.epos.auth_service.repository.MatiereRepository;

import java.util.List;

@RestController
@RequestMapping("/api/v1/matieres")
@RequiredArgsConstructor
public class MatiereController {

    private final MatiereRepository matiereRepository;

    /**
     * GET /api/v1/matieres
     * Lists all pharmacy subjects. Used by frontend pickers
     * (exam creation, role assignment). Any authenticated user.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<MatiereResponse>>> list() {
        List<MatiereResponse> body = matiereRepository.findAll()
                .stream()
                .map(MatiereResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
