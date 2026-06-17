import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { UserResponse } from '../api/models';

@Injectable({ providedIn: 'root' })
export class ProfilService {
  // Simule l'utilisateur actuellement connecté
  private mockUser: UserResponse = {
    id: 1,
    nom: 'Admin',
    prenom: 'Dr.',
    email: 'admin.epos@univ.tn',
    isActive: true,
    createdAt: '2023-09-12'
  };

  getConnectedUser(): Observable<UserResponse> {
    return of(this.mockUser);
  }

  updateProfil(data: any): Observable<any> {
    console.log('Mise à jour profil demandée:', data);
    return of({ success: true });
  }
}