import { Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';
import { GrilleDetail } from '../api/models'; // Ajustez le chemin si nécessaire

@Injectable({
  providedIn: 'root'
})
export class TemplateService { // <--- BIEN VÉRIFIER LE MOT "export" ICI
  
  private mockTemplates: GrilleDetail[] = [
    {
      id: 1,
      nom: 'Grille Suture Simple',
      noteMax: 20,
      description: 'Modèle standard pour bloc opératoire',
      items: []
    }
  ];

  constructor() {}

  getTemplates(): Observable<GrilleDetail[]> {
    return of(this.mockTemplates);
  }

  saveTemplate(template: GrilleDetail): Observable<GrilleDetail> {
    console.log('Simulation sauvegarde:', template);
    return of(template);
  }
}