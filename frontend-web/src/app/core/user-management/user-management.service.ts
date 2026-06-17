// src/app/features/admin/user-management.service.ts
import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { UserResponse } from '../../core/api/models';

// On crée une version étendue pour le mock car le rôle est visuel
export interface UserUI extends UserResponse {
  role: string; // Ajouté pour la démo
}

@Injectable({ providedIn: 'root' })
export class UserManagementService {
  
  private mockUsers: UserUI[] = [
    { id: 1, nom: 'Ben Salem', prenom: 'Amina', email: 'amina.bensalem@epos.tn', isActive: true, role: 'Évaluateur', createdAt: '2023-10-01' },
    { id: 2, nom: 'Gharbi', prenom: 'Hatem', email: 'hatem.gharbi@epos.tn', isActive: true, role: 'Administrateur', createdAt: '2023-11-15' },
    { id: 3, nom: 'Zribi', prenom: 'Faten', email: 'faten.zribi@epos.tn', isActive: false, role: 'Responsable', createdAt: '2024-01-10' }
  ];

  getUsers(): Observable<UserUI[]> {
    // Simule un appel API
    return of(this.mockUsers);
  }
}