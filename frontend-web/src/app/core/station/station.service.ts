import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';

export interface StationUI {
  id: number;
  nom: string;
  type: 'PRATIQUE' | 'THEORIQUE';
  ordre: number;
  description: string;
  grilleNom: string; // Stocke le nom du modèle choisi
}

@Injectable({ providedIn: 'root' })
export class StationService {
  
  private mockStations: StationUI[] = [
    { id: 1, nom: 'Auscultation Pulmonaire', type: 'PRATIQUE', ordre: 1, description: 'Examen des bases', grilleNom: 'Grille Clinique Standard' },
    { id: 2, nom: 'Interprétation Radio', type: 'THEORIQUE', ordre: 2, description: 'Analyse cliché thorax', grilleNom: 'Questionnaire QCM' },
  ];

  // Liste des modèles de grilles pour le formulaire
  getGrilleTemplates(): Observable<string[]> {
    return of([
      'Grille Clinique Standard', 
      'Grille de Communication', 
      'Grille Technique Chirurgicale', 
      'Questionnaire QCM',
      'Évaluation Comportementale'
    ]);
  }

  getStations(): Observable<StationUI[]> {
    return of(this.mockStations);
  }
}