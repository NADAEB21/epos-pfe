import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { MatiereResponse } from '../api/models';

@Injectable({
  providedIn: 'root'
})
export class MatiereService {

  // Données de test basées sur votre entité Java
  private mockMatieres: MatiereResponse[] = [
    { id: 1, code: 'PHARM-G', libelle: 'Pharmacie Galénique', createdAt: '2023-01-15T10:00:00' },
    { id: 2, code: 'TOXIC', libelle: 'Toxicologie', createdAt: '2023-02-10T09:30:00' },
    { id: 3, code: 'CH-THER', libelle: 'Chimie Thérapeutique', createdAt: '2023-03-05T14:20:00' },
    { id: 4, code: 'PHARM-C', libelle: 'Pharmacologie', createdAt: '2023-04-12T11:00:00' },
    { id: 5, code: 'BIO-CLIN', libelle: 'Biochimie Clinique', createdAt: '2023-05-20T08:45:00' }
  ];

  constructor() {}

  /**
   * Simule la récupération de la liste des matières
   */
  getMatieres(): Observable<MatiereResponse[]> {
    return of(this.mockMatieres);
  }
}